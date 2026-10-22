package com.dwinovo.numen.experience.api;

/**
 * G6c：一条经验在**试用期**里的使用事实。
 *
 * <p><b>只装事实，不装判断</b>：成功/失败来自真实使用记录，
 * 「该不该转正」由 {@link TrialVerdict} 按写死的规则回答。
 * 混在一起就会出现「因为我想让它转正，所以统计口径变了」这类事。
 *
 * @param uses        被用过几次
 * @param successes   其中判成功的次数
 * @param failures    其中判失败的次数（{@code uses - successes - cancelled} 不一定相等，
 *                    取消/中断既不算成功也不算失败）
 * @param lastUsedMillis 最后一次使用的时间（0 = 没用过）
 */
public record TrialStats(int uses, int successes, int failures, long lastUsedMillis) {

    public static final TrialStats EMPTY = new TrialStats(0, 0, 0, 0L);

    public TrialStats {
        if (uses < 0) uses = 0;
        if (successes < 0) successes = 0;
        if (failures < 0) failures = 0;
        if (successes + failures > uses) {
            // 自相矛盾的数据：按「成功少一点」修，而不是抛 —— 统计坏掉不该让试用卡死。
            // 但也不许把失败裁掉：失败是最不能丢的那个数。
            successes = Math.max(0, uses - failures);
        }
    }

    /** 既没成功也没失败的次数（取消/中断）。 */
    public int inconclusive() {
        return Math.max(0, uses - successes - failures);
    }
}
