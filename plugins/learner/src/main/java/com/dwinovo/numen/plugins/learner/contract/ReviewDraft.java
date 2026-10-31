package com.dwinovo.numen.plugins.learner.contract;

import com.dwinovo.numen.plugins.learner.blocks.BlockKind;
import com.dwinovo.numen.plugins.learner.blocks.EnvBlock;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * 复盘草稿的<strong>证据硬约束</strong>（158 §5，159 B/E）。
 *
 * <h2>它拦的是什么</h2>
 * 复盘最容易产出的坏东西不是"错的结论"，而是"没有证据的结论"——
 * 它读起来完全正常，却无法核验，于是会一路变成经验、进检索、影响行为，
 * 最后没人能解释这条经验是从哪来的。
 *
 * <h2>三档结论，刻意不合并成两档</h2>
 * <ul>
 *   <li>{@link Status#PASS} —— 每条断言都有能当场核验的证据；</li>
 *   <li>{@link Status#PASS_WITH_UNRESOLVED} —— 结构合格，但有证据当前核验不了
 *       （引用不存在、或引用了原始事件）。<b>不许折成 PASS</b>：161 §6.2 记的就是
 *       「推不出来的项被填成通过」，那会让整道门永远绿；</li>
 *   <li>{@link Status#REJECT} —— 结构不合格（无断言、无证据、决策点没引上下文）。</li>
 * </ul>
 *
 * <h2>为什么决策点必须引用 {@code DECISION_CONTEXT}</h2>
 * 158 §5 原文：「决策点必须引用 DecisionContextBlock」。
 * 因为"当时可以做得不一样"这个判断，只有对着执行者**真正收到的内容**才成立；
 * 拿事后日志反推出来的"当时",任何决策点都是站不住的。
 *
 * <p>纯 JVM、无 IO（块查找由调用方注入），可离线单测。
 */
public final class ReviewDraft {

    /** 复盘九段（158 §5 的顺序）。 */
    public enum Section {
        FACT("事件事实"),
        DIRECT_CAUSE("直接原因"),
        CONTRIBUTING("促成条件"),
        UPSTREAM("上游原因"),
        DECISION_POINT("可改变决策点"),
        COVERAGE("已有能力覆盖"),
        GAP("真实缺口"),
        ARTIFACT("候选产物"),
        COUNTEREXAMPLE("范围与反例");

        private final String zh;

        Section(String zh) {
            this.zh = zh;
        }

        public String zh() {
            return zh;
        }
    }

    /**
     * 一条断言。
     *
     * @param evidence 字段级证据引用（158 §5：每个事实性断言都要有）
     */
    public record Claim(Section section, String text, List<EvidenceRef> evidence) {
        public Claim {
            if (section == null) {
                throw new IllegalArgumentException("断言必须属于某一段");
            }
            text = text == null ? "" : text.trim();
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /** 一份草稿。 */
    public record Draft(String sampleId, List<Claim> claims) {
        public Draft {
            sampleId = sampleId == null ? "" : sampleId.trim();
            claims = claims == null ? List.of() : List.copyOf(claims);
        }
    }

    /** 结论档位。 */
    public enum Status {
        PASS,
        PASS_WITH_UNRESOLVED,
        REJECT
    }

    /**
     * 一处问题。
     *
     * @param code 稳定代码（给统计用，别用自然语言当键）
     */
    public record Violation(String code, String detail) {
    }

    /** 校验结果。 */
    public record Validation(Status status, List<Violation> violations, List<String> unresolved) {
        public Validation {
            violations = violations == null ? List.of() : List.copyOf(violations);
            unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
        }

        public boolean accepted() {
            return status != Status.REJECT;
        }

        public List<String> codes() {
            return violations.stream().map(Violation::code).toList();
        }
    }

    private ReviewDraft() {
    }

    /**
     * 校验。
     *
     * @param blockLookup 按块 id 查块；{@code null} 视为"什么都查不到"
     */
    public static Validation validate(Draft draft, Function<String, Optional<EnvBlock>> blockLookup) {
        List<Violation> violations = new ArrayList<>();
        Set<String> unresolved = new LinkedHashSet<>();

        if (draft == null || draft.sampleId().isBlank()) {
            violations.add(new Violation("no_sample", "草稿没有绑定样本 id"));
            return new Validation(Status.REJECT, violations, List.of());
        }
        if (draft.claims().isEmpty()) {
            violations.add(new Violation("no_claims", "一条断言都没有：这不是复盘，是空的"));
            return new Validation(Status.REJECT, violations, List.of());
        }
        if (draft.claims().stream().noneMatch(c -> c.section() == Section.FACT)) {
            violations.add(new Violation("no_fact", "没有「事件事实」段：没有事实就没有归因的起点"));
        }

        boolean sawDecisionPoint = false;
        boolean decisionPointBacked = false;

        for (int i = 0; i < draft.claims().size(); i++) {
            Claim c = draft.claims().get(i);
            String at = "#" + i + "(" + c.section().name() + ")";
            if (c.text().isBlank()) {
                violations.add(new Violation("empty_claim", at + " 正文为空"));
            }
            if (c.evidence().isEmpty()) {
                violations.add(new Violation("claim_without_evidence", at + " 没有证据引用"));
                if (c.section() == Section.DECISION_POINT) {
                    sawDecisionPoint = true;
                }
                continue;
            }
            for (EvidenceRef ref : c.evidence()) {
                if (!ref.verifiableOffline()) {
                    // 事件引用要回原始日志，离线核不了：记成"未核实"，不折成通过。
                    unresolved.add(ref.key());
                    continue;
                }
                Optional<EnvBlock> b = blockLookup == null ? Optional.empty() : blockLookup.apply(ref.id());
                if (b.isEmpty()) {
                    unresolved.add(ref.key());
                    continue;
                }
                if (c.section() == Section.DECISION_POINT && b.get().kind() == BlockKind.DECISION_CONTEXT) {
                    decisionPointBacked = true;
                }
            }
            if (c.section() == Section.DECISION_POINT) {
                sawDecisionPoint = true;
            }
        }

        if (sawDecisionPoint && !decisionPointBacked) {
            violations.add(new Violation("decision_point_without_context",
                    "决策点必须引用决策上下文块：拿事后日志反推的「当时」撑不住任何决策点"));
        }

        if (!violations.isEmpty()) {
            return new Validation(Status.REJECT, violations, List.copyOf(unresolved));
        }
        return new Validation(unresolved.isEmpty() ? Status.PASS : Status.PASS_WITH_UNRESOLVED,
                List.of(), List.copyOf(unresolved));
    }
}
