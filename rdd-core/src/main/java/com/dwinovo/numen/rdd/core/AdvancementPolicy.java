package com.dwinovo.numen.rdd.core;

/**
 * 成就完成判定的**状态转换表**（037/T3 第一轮 · EV-01，2026-11-04）。
 *
 * <p>为什么要这张表：{@link AdvancementVerification} 早就能分出四态，但执行侧仍是
 * {@code advancement(...) → isDone()} 的布尔路径。布尔把
 *「确实没完成」{@code NOT_MATCHED}、「条件本身写错」{@code INVALID}、
 *「观测不可用」{@code NOT_VERIFIABLE} 压成同一个 {@code false}：
 * 要么无限重试同一个动作、把锅扣在执行者头上，要么永远沉默。
 *
 * <p>这张表把"每个状态该干什么"固化成可测常量，第二轮 Detector 只能按表分支：
 * <ul>
 *   <li>{@code MATCHED}：唯一能当完成证据的状态；</li>
 *   <li>{@code NOT_MATCHED}：真的没做完 → 维持现有等待/失败策略；</li>
 *   <li>{@code INVALID}：条件可证明是错的 → 去改条件/重规划，**不记动作失败**、也不自动完成；</li>
 *   <li>{@code NOT_VERIFIABLE}：观测不可用 → 限频诊断、等刷新，**不记动作失败**、也不永久沉默。</li>
 * </ul>
 */
public final class AdvancementPolicy {

    private AdvancementPolicy() {}

    /** 判定之后允许的下一步。 */
    public enum Next {
        /** 拿到完成证据，可以推进（只有 MATCHED）。 */
        CONTINUE,
        /** 动作没做完：走现有等待/失败/重试策略。 */
        RETRY,
        /** 条件错了：改条件或重规划，不怪执行者。 */
        REPAIR_CONDITION,
        /** 观测不可用：限频诊断 + 等刷新，不记失败。 */
        DIAGNOSE_WAIT
    }

    /**
     * @param result                四态
     * @param completionEvidence    是否可作为"完成"证据（只有 MATCHED 为 true）
     * @param countsAsActionFailure 是否计入"动作失败/重试次数"
     * @param next                  下一步走向
     */
    public record Rule(AdvancementVerification.Result result,
                       boolean completionEvidence,
                       boolean countsAsActionFailure,
                       Next next) {
    }

    /** 全表：四态一个不漏，值固定（EV-01 第一轮冻结）。 */
    public static Rule of(AdvancementVerification.Result result) {
        if (result == null) {
            throw new IllegalArgumentException("result required");
        }
        return switch (result) {
            case MATCHED -> new Rule(result, true, false, Next.CONTINUE);
            // 真的没做完：走现有等待/失败策略，不动它。
            case NOT_MATCHED -> new Rule(result, false, true, Next.RETRY);
            // 条件本身可证明是错的：去修条件/重规划；不算执行失败，也不许自动完成。
            case INVALID -> new Rule(result, false, false, Next.REPAIR_CONDITION);
            // 观测不可用：限频诊断、等刷新；不算失败，也不许永久沉默。
            case NOT_VERIFIABLE -> new Rule(result, false, false, Next.DIAGNOSE_WAIT);
        };
    }

    /** 旧布尔消费口径的等价物（只用于对照，不要在新代码里用）。 */
    public static boolean legacyBoolean(AdvancementVerification.Result result) {
        return result == AdvancementVerification.Result.MATCHED;
    }

    /** 能不能当"本步完成"（与 {@link #of} 的 completionEvidence 同源，给执行侧直接用）。 */
    public static boolean completesStep(AdvancementVerification.Result result) {
        return of(result).completionEvidence();
    }

    /** 条件本身错了 → 该去修条件/重规划（本步不算执行失败）。 */
    public static boolean shouldRequestRepair(AdvancementVerification.Result result) {
        return of(result).next() == Next.REPAIR_CONDITION;
    }

    /** 观测不可用 → 只诊断、等刷新（既不算失败，也不许永久沉默）。 */
    public static boolean shouldDiagnoseOnly(AdvancementVerification.Result result) {
        return of(result).next() == Next.DIAGNOSE_WAIT;
    }
}
