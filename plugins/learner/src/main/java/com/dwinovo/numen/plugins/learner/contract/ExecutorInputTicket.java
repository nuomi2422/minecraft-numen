package com.dwinovo.numen.plugins.learner.contract;

import com.dwinovo.numen.plugins.learner.blocks.BlockKind;
import com.dwinovo.numen.plugins.learner.blocks.EnvBlock;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * `NEEDS_EXECUTOR_INPUT` 的请求/响应链（158 §1、159 B）。
 *
 * <h2>它解决的是什么问题</h2>
 * 内部学习者只看得到模组按权限给的那一小段材料。它经常必须问一句
 * 「你当时到底看到什么了」才能归因 —— 但「打电话问」这件事本身是**证据风险**：
 * 回答可能是执行者事后编的、可能是别人补的、也可能是模型自己脑补的。
 * 所以这里把它做成一张<strong>票据</strong>：请求带截止时间，回答必须挂到
 * 执行者当时真的收到的内容（{@link BlockKind#DECISION_CONTEXT} 块）上才算 {@link State#VERIFIED}。
 *
 * <h2>三个状态必须分开，不能合成"已回答"</h2>
 * <ul>
 *   <li>{@link State#VERIFIED} —— 回答能追到决策上下文块；</li>
 *   <li>{@link State#UNVERIFIED} —— 收到了话，但追不到当时的上下文（可能是记忆、可能是猜）；</li>
 *   <li>{@link State#EXPIRED} —— 超时，答案不采信。</li>
 * </ul>
 * 合并成一个"已回答"会让复盘时无法区分「有据的回答」与「听起来像的回答」——
 * 而后者一旦被当成证据，就会生成一条查不出来的错误经验。
 *
 * <h2>为什么超时后连 UNVERIFIED 都不给</h2>
 * 样本会归档、世界会换代。过期之后补上来的"我当时看到"已经无法与当时的环境对齐，
 * 收下它只会让样本挂一条对不上的注释。
 *
 * <p>纯 JVM、无 IO、时钟由调用方传入。
 */
public final class ExecutorInputTicket {

    /** 票据状态。 */
    public enum State {
        /** 已发出，等回答。 */
        OPEN,
        /** 回答已到，且能追到决策上下文块。 */
        VERIFIED,
        /** 回答已到，但追不到当时的上下文。 */
        UNVERIFIED,
        /** 超时。 */
        EXPIRED,
        /** 主动关闭（样本已被处理掉等）。 */
        CLOSED
    }

    /**
     * 一次请求。
     *
     * @param missingFacts 缺哪些事实（要具体，不能写"缺信息"）
     * @param deadlineAt   截止时刻
     */
    public record Request(String requestId, String sampleId, String question,
                          List<String> missingFacts, long at, long deadlineAt) {
        public Request {
            if (requestId == null || requestId.isBlank()) {
                throw new IllegalArgumentException("requestId 不能为空");
            }
            if (sampleId == null || sampleId.isBlank()) {
                throw new IllegalArgumentException("请求必须绑定样本");
            }
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("问题不能为空");
            }
            missingFacts = missingFacts == null ? List.of() : List.copyOf(missingFacts);
            if (deadlineAt <= at) {
                throw new IllegalArgumentException("截止时间必须晚于发出时间");
            }
        }
    }

    /**
     * 一次回答。
     *
     * @param evidence 证据引用（要能指到决策上下文块才可能 VERIFIED）
     */
    public record Answer(String requestId, String text, List<EvidenceRef> evidence, long at) {
        public Answer {
            requestId = requestId == null ? "" : requestId.trim();
            text = text == null ? "" : text;
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /** 处理结果。 */
    public record Result(boolean accepted, State state, String note) {
        public Result {
            note = note == null ? "" : note;
        }
    }

    private final Request request;
    private State state = State.OPEN;
    private Answer answer;
    private String note = "";

    private ExecutorInputTicket(Request request) {
        this.request = request;
    }

    public static ExecutorInputTicket open(Request request) {
        return new ExecutorInputTicket(request);
    }

    public Request request() {
        return request;
    }

    public State state() {
        return state;
    }

    public Optional<Answer> answer() {
        return Optional.ofNullable(answer);
    }

    public String note() {
        return note;
    }

    public boolean settled() {
        return state != State.OPEN;
    }

    /**
     * 收下回答。
     *
     * @param blockLookup 按块 id 查块（用来判断证据是不是真的决策上下文）
     */
    public synchronized Result answer(Answer a, Function<String, Optional<EnvBlock>> blockLookup) {
        if (state != State.OPEN) {
            // 重复作答会让"最终采信的是哪一条"不可查 —— 直接拒。
            return new Result(false, state, "ticket_not_open");
        }
        if (a == null) {
            return new Result(false, state, "no_answer");
        }
        if (a.at() > request.deadlineAt()) {
            state = State.EXPIRED;
            note = "answered_after_deadline";
            return new Result(false, state, note);
        }
        if (!a.requestId().isBlank() && !a.requestId().equals(request.requestId())) {
            return new Result(false, state, "request_id_mismatch");
        }
        if (a.text().isBlank() && a.evidence().isEmpty()) {
            return new Result(false, state, "empty_answer");
        }

        boolean citesContext = false;
        for (EvidenceRef ref : a.evidence()) {
            if (!ref.verifiableOffline()) {
                continue;
            }
            Optional<EnvBlock> b = blockLookup == null ? Optional.empty() : blockLookup.apply(ref.id());
            // 必须是**决策上下文**块：样本摘要块也能查到，但它证明不了"执行者当时收到了什么"。
            if (b.isPresent() && b.get().kind() == BlockKind.DECISION_CONTEXT) {
                citesContext = true;
                break;
            }
        }

        answer = a;
        if (citesContext) {
            state = State.VERIFIED;
            note = "cites_decision_context";
        } else {
            state = State.UNVERIFIED;
            note = a.evidence().isEmpty() ? "no_evidence" : "no_decision_context_ref";
        }
        return new Result(true, state, note);
    }

    /** 主动关闭。已经落定的票据不受影响。 */
    public synchronized Result close(String why) {
        if (state != State.OPEN) {
            return new Result(false, state, "already_settled");
        }
        state = State.CLOSED;
        note = why == null ? "" : why;
        return new Result(true, state, note);
    }
}
