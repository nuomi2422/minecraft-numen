package com.dwinovo.numen.plugins.rdd;

/**
 * 修复兜底日志的最小纯策略（2026-11-06，热点修复）。
 *
 * <p>背景：{@code RddDetector} 每 tick 调 {@code RddRepairDispatch.recomputeLatestFromLedger}，
 * 而该方法在幂等 guard 之前打 enter/latest 两条 INFO，实测 12:40:11–12:41:11 每秒 2 行、
 * latest.log 4.6MB/6 分钟。幂等 guard 已挡住重算，但没挡日志。
 *
 * <p>口径：
 * <ul><li>{@link #skipSameBatch} 为 true 时调用方必须直接 return（不重算、不打 INFO）；</li>
 * <li>{@link #shouldLogArmed} 每同伴每进程只 true 一次，用来保留"兜底真的被调到"的可观测性；</li>
 * <li>无新增长期状态库：armed 集合由调用方持有，进程结束即失效。</li></ul>
 */
final class RddRepairLogPolicy {

    private RddRepairLogPolicy() {}

    /** 同一笔死亡（seq 相同）→ 跳过重算与日志；新死亡（seq 变）才继续。 */
    static boolean skipSameBatch(long lastRecomputedSeq, long currentSeq) {
        return lastRecomputedSeq == currentSeq;
    }

    /** 每同伴每进程只记一次 armed。 */
    static boolean shouldLogArmed(java.util.Set<java.util.UUID> armed, java.util.UUID companionId) {
        return companionId != null && armed.add(companionId);
    }
}
