package com.dwinovo.numen.experience.api;

/**
 * 一次「经验被用到」的**终局状态**（G6a/IC11 五态）。
 *
 * <p><b>为什么五态而不是三态</b>：{@link TrialUse.Outcome} 把「取消 / 中断 / 未知」合并成
 * {@code INCONCLUSIVE}，这对「成功率」够用，对「失败/中断不漏记」不够 ——
 * 合并之后就分不清「这次是取消（不算失败）」和「这次根本没跑完（该补证据）」。
 *
 * <p><b>口径</b>：
 * <ul>
 *   <li>{@link #SUCCESS} 任务收尾 done/success；</li>
 *   <li>{@link #FAILURE} failed/timeout —— <b>取消不算失败</b>；</li>
 *   <li>{@link #CANCELED} 被取消（含身体/任务被替换）；</li>
 *   <li>{@link #INTERRUPTED} 中断（停服、玩家退出、进程被杀）；</li>
 *   <li>{@link #UNKNOWN} 有这次使用、但没等到对应任务的终态 —— **显式记，不许静默丢**。</li>
 * </ul>
 */
public enum UsageState {

    SUCCESS,
    FAILURE,
    CANCELED,
    INTERRUPTED,
    UNKNOWN;

    /** 是不是「等到了一次明确终态」。UNKNOWN 不算。 */
    public boolean settled() {
        return this != UNKNOWN;
    }

    /** 算不算失败（取消/中断/未知都不算 —— 这是 TrialUse 已有的口径，这里保持一致）。 */
    public boolean countsAsFailure() {
        return this == FAILURE;
    }

    /** 容错解析：null/未知 → UNKNOWN（宁可记「不知道」，也不许猜成成功）。 */
    public static UsageState parse(String raw) {
        if (raw == null || raw.isBlank()) return UNKNOWN;
        String s = raw.trim().toUpperCase(java.util.Locale.ROOT);
        // 线上真实状态名并不统一（游戏侧有 stopped/done/cancelled 等）：先按别名归一，再走枚举
        switch (s) {
            case "DONE", "SUCCESS", "COMPLETED", "OK" -> { return SUCCESS; }
            case "FAILED", "FAILURE", "ERROR", "TIMEOUT" -> { return FAILURE; }
            case "CANCELED", "CANCELLED", "STOPPED", "ABORTED" -> { return CANCELED; }
            case "INTERRUPTED", "KILLED", "CRASHED" -> { return INTERRUPTED; }
            default -> { }
        }
        try {
            return UsageState.valueOf(s);
        } catch (IllegalArgumentException ignored) {
            return UNKNOWN;
        }
    }
}
