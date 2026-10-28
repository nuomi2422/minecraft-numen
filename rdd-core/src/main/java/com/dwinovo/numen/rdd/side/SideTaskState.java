package com.dwinovo.numen.rdd.side;

/** 支线的通用生命周期状态（与具体支线类型无关）。 */
public enum SideTaskState {
    CREATED,
    ACTIVE,
    COMPLETED,
    /** 超时跳过：不是失败，不触发主线 FAILED / 重规划。 */
    SKIPPED_TIMEOUT,
    /** 取消：主人暂停/换目标/清目标/世界切换等。 */
    CANCELLED,
    /**
     * 执行 AI 自己放弃（2026-10-28，主人要的「支线可跳过」）。
     *
     * <p>与 {@link #CANCELLED} 只差「谁下的手」：CANCELLED 是外部（主人/换目标/换世界），
     * DECLINED 是执行侧看着现场说「这条现在不值得/做不到」（太危险、没工具、赶不上）。
     * 监测台必须能把两者分开 —— 否则「她自己拒绝了」会被读成「有人取消了」，
     * 而这两件事对复盘的含义完全相反。
     */
    DECLINED
}
