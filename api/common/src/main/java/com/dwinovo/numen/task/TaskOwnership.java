package com.dwinovo.numen.task;

/**
 * 身体任务「谁有权占用」的受理契约（037/T5 第一轮 · OWN-01，2026-11-04）。
 *
 * <p><b>为什么要有它</b>：现在同一件事有两套说法。{@code NumenActuator} 的类注释写着
 * 「身体上有活时新派的活当场被拒」，而 {@code TaskDispatch.setTask} 的实现是
 * 「槽里原来那件活会被替换（收到取消结果），不拒绝新的」，唯一拒绝的情形是
 * 「同一批工具调用里刚受理、一刻都没跑过」。文档与实现不一致，谁也没法据此判断
 * 一次受理到底是抢占、替换还是被拒。
 *
 * <p>第一轮只冻结判定，不改 {@code TaskDispatch}（改动放第二轮，先列调用者再动）。
 * 判定要素：每次受理都要有 companionId / taskId / owner / generation，替换必须说明旧 task 与策略。
 */
public final class TaskOwnership {

    /** 谁在占这具身体。{@code UNKNOWN} 用于旧调用方（没有 owner 的存量代码）。 */
    public enum Owner {
        /** 主人（面板/主人指令）。 */
        OWNER,
        /** 同伴自己的内置大脑。 */
        BUILTIN_BRAIN,
        /** 外接大脑（MCP / 外部代理）。 */
        EXTERNAL_BRAIN,
        /** ACX 脚本执行层。 */
        ACX,
        /** 安全/紧急通道（保命、防坠、抗火等）。 */
        SAFETY,
        /** 老调用方：没有 owner 概念。 */
        UNKNOWN
    }

    /** 一次受理请求。{@code source} 是审计用的来源串，紧急通道必须显式给出。 */
    public record Request(String companionId, String taskId, Owner owner, long generation,
                          String source, boolean emergency) {
    }

    /** 槽里当前那件活。 */
    public record Current(String taskId, Owner owner, long generation) {
    }

    public enum Decision {
        /** 槽是空的，直接受理。 */
        ACCEPT,
        /** 顶替掉当前任务（旧任务会被取消）。 */
        REPLACE,
        /** 身体在忙且来者无权顶替：回忙，不冒充完成。 */
        BUSY,
        /** 来者与占位者冲突（不同 owner / 陈旧代次），要人裁决或换证据。 */
        CONFLICT
    }

    /** @param reason 给模型/审计看的白话原因，不能为空。 */
    public record Outcome(Decision decision, String reason) {
    }

    private TaskOwnership() {}

    /** 判定一次受理（OWN-01 冻结版）。 */
    public static Outcome decide(Current current, Request incoming) {
        if (incoming == null) {
            return new Outcome(Decision.CONFLICT, "missing request");
        }
        if (current == null) {
            return new Outcome(Decision.ACCEPT, "body free");
        }
        Owner owner = incoming.owner() == null ? Owner.UNKNOWN : incoming.owner();
        Owner holder = current.owner() == null ? Owner.UNKNOWN : current.owner();

        // 迟到的旧代次：不许顶替更新的一代（迟到回调就是靠这条挡住）。
        if (incoming.generation() < current.generation()) {
            return new Outcome(Decision.CONFLICT, "stale generation " + incoming.generation()
                    + " < current generation " + current.generation() + "; the newer task keeps the body");
        }
        // 安全通道：可以跨 owner 强占，但必须给得出显式来源，否则事后无法审计。
        if (owner == Owner.SAFETY || incoming.emergency()) {
            String source = incoming.source() == null ? "" : incoming.source().trim();
            if (source.isEmpty()) {
                return new Outcome(Decision.CONFLICT,
                        "safety takeover requires an explicit source so it can be audited");
            }
            return new Outcome(Decision.REPLACE, "safety takeover from " + source
                    + " replacing " + current.taskId() + " (old task is cancelled, not finished)");
        }
        // 主人明确抢占：保留。
        if (owner == Owner.OWNER) {
            return new Outcome(Decision.REPLACE, "owner preemption replacing " + current.taskId()
                    + " (old task is cancelled, not finished)");
        }
        // 同一 owner 换任务：常态替换，必须点名旧任务。
        if (owner == holder) {
            return new Outcome(Decision.REPLACE, "same owner " + owner + " replacing " + current.taskId()
                    + " (old task is cancelled, not finished)");
        }
        // 旧调用方（没有 owner）默认没有最高权限：只能等，不能顶替。
        if (owner == Owner.UNKNOWN || holder == Owner.UNKNOWN) {
            return new Outcome(Decision.BUSY, "body busy with " + current.taskId() + " (" + holder
                    + "); a caller without an owner cannot preempt it");
        }
        return new Outcome(Decision.CONFLICT, "owner conflict: " + owner + " cannot replace "
                + current.taskId() + " owned by " + holder);
    }
}
