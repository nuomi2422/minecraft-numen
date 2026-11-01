package com.dwinovo.numen.rdd.core;

import java.util.List;

/**
 * 从多个 world_base 观测里挑出**当前那个家**（2026-11-01 实机查明的缺陷）。
 *
 * <p>实机证据：`asset_snapshot` 里 base 观测共 5664 条，分布在 **6 个不同坐标**上
 * （`371,73,717` / `370,68,715` / `371,68,715` / … 各 518 次）——因为资产 id 里带坐标，
 * 它每换一次床就多出一个「基地」。
 * 而原来的取法是「取最新的一条」，于是经常拿到**别的床旁边那个空箱子**，
 * 上下文里就一直是 `reserve=KNOWN but no critical items`（虽然 1554 条观测里明明有铁镐）。
 *
 * <p>所以：按**当前重生点**挑，而不是按时间挑。挑不到才退回最新一条。
 */
public final class BaseSelector {

    private BaseSelector() {}

    /** 一个候选基地观测。 */
    public record Candidate(int x, int y, int z, long observedAtMillis) {}

    /** 默认容忍半径：重生点与基地快照坐标不会完全相等（床占两格）。 */
    public static final double DEFAULT_RADIUS = 4.0;

    /**
     * @param candidates 候选（按任意顺序）
     * @param respawnX/Y/Z 当前重生点；传 null 或哨兵坐标表示未绑定
     * @return 选中的下标；没有候选返回 -1
     */
    public static int pick(List<Candidate> candidates, Integer respawnX, Integer respawnY, Integer respawnZ) {
        if (candidates == null || candidates.isEmpty()) return -1;
        if (respawnX != null && respawnY != null && respawnZ != null) {
            int best = -1;
            double bestDist = Double.MAX_VALUE;
            for (int i = 0; i < candidates.size(); i++) {
                Candidate c = candidates.get(i);
                double d = Math.sqrt(Math.pow(c.x() - respawnX, 2)
                        + Math.pow(c.y() - respawnY, 2) + Math.pow(c.z() - respawnZ, 2));
                if (d <= DEFAULT_RADIUS && d < bestDist) { bestDist = d; best = i; }
            }
            if (best >= 0) return best;
        }
        int newest = 0;
        for (int i = 1; i < candidates.size(); i++) {
            if (candidates.get(i).observedAtMillis() > candidates.get(newest).observedAtMillis()) newest = i;
        }
        return newest;
    }
}