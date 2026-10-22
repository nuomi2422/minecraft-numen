package com.dwinovo.numen.experience.api;

/**
 * 一份「使用账」的派生结果（G6a/IC11）。纯数据，可重算。
 *
 * @param success      成功次数
 * @param failure      失败次数
 * @param canceled     取消次数
 * @param interrupted  中断次数
 * @param unknown      没等到终态的次数（显式记，不许静默丢）
 * @param duplicates   重复投递被丢掉的条数（不算样本，但要看得到）
 * @param excludedByVersion 版本对不上因此没进这次统计的条数（L4：不按名字猜版本）
 * @param conclusion   结论：**最后一次尝试**的终态；没有样本时是 {@link UsageState#UNKNOWN}
 * @param lastAttempt  最后一次尝试的 attempt 号；没有样本时 -1
 * @param hadCancel    同一条（同产物）里是否出现过取消 —— 与 conclusion 并存，不互相覆盖
 */
public record UsageStats(
        int success,
        int failure,
        int canceled,
        int interrupted,
        int unknown,
        int duplicates,
        int excludedByVersion,
        UsageState conclusion,
        int lastAttempt,
        boolean hadCancel) {

    public static final UsageStats EMPTY =
            new UsageStats(0, 0, 0, 0, 0, 0, 0, UsageState.UNKNOWN, -1, false);

    public UsageStats {
        conclusion = conclusion == null ? UsageState.UNKNOWN : conclusion;
    }

    /** 进了账的样本数（五种状态之和）。 */
    public int total() {
        return success + failure + canceled + interrupted + unknown;
    }

    /** 有明确终态的比例分母用 total()；没有样本时返回 0（不除零）。 */
    public double successRate() {
        int t = total();
        return t == 0 ? 0.0 : (double) success / t;
    }
}
