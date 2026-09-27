package com.dwinovo.numen.plugins.rdd;

/** Pure supervision policy; activity evidence is separate from merely having a task in flight. */
final class RddStallPolicy {
    static final int IDLE_GRACE_CHECKS = 15;
    /** One check per second. A busy slot alone cannot suppress supervision forever. */
    static final int WORK_GRACE_CHECKS = 120;

    /**
     * 坐标量化成 8 格粗桶（纯函数，无 MC 依赖，便于单测锁住"区域内抖动同桶、跨区域换桶"）。
     *
     * <p>2026-09-27 实机教训：停车守望原用**精确坐标**判进展，AI 在两点之间横跳（寻路打转、
     * 到不了目标）时坐标每 tick 变化 -> "无进展"窗口被无限重置 -> 催工永不触发，
     * 表现为"它自己一直重复走、任务永远完不成、也没人拍醒"。粗桶让区域内抖动不再重置窗口。
     */
    static String parkedBucket(int x, int y, int z) {
        return Math.floorDiv(x, 8) + "," + Math.floorDiv(y, 8) + "," + Math.floorDiv(z, 8);
    }

    record Observation(String assetsAndPosition, String workProgress, boolean waiting, String source) {
        String fingerprint() { return assetsAndPosition + "|work=" + workProgress; }
    }

    record Check(String fingerprint, int unchanged, int limit, boolean changed) {
        boolean stalled() { return unchanged >= limit; }
        int remaining() { return Math.max(0, limit - unchanged); }
    }

    static Check check(String previous, int unchanged, Observation observation) {
        String fingerprint = observation.fingerprint();
        boolean changed = !fingerprint.equals(previous);
        int next = changed ? 0 : unchanged + 1;
        return new Check(fingerprint, next,
                observation.waiting() ? WORK_GRACE_CHECKS : IDLE_GRACE_CHECKS, changed);
    }
}
