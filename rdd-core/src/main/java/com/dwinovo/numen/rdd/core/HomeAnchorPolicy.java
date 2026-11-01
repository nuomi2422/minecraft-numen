package com.dwinovo.numen.rdd.core;

/**
 * 家锚点策略（167 B4-1 的语义补齐，2026-11-01 由施工方决定并记录）。
 *
 * <p>问题：原先「家」= 最新绑定的床。它一路走一路绑新床（实机：respawn 一天内换了三处，
 * base 观测散落在 6 个坐标上），于是「家」跟着人跑，备用箱永远不达标。
 *
 * <p>本文语义（选项 B）：**第一次绑定即为家**；之后再绑床只更新复活点，不改家。
 * 理由：安家的直觉是「先有个据点，之后走哪算哪」，而不是「走到哪家在哪」；
 * 而且这让 `STOCKED` 可达——否则永远在给新床旁的空气箱补货。
 *
 * <p>边界：这是一条**产品语义**，已写在 167 §9.14；若主人改口径，改这里即可（纯函数，无落盘）。
 */
public final class HomeAnchorPolicy {

    private HomeAnchorPolicy() {}

    /** 一个家锚点。 */
    public record Anchor(String dimension, int x, int y, int z, long setAtMillis) {}

    /**
     * 决定「家」应该是哪个。
     *
     * @param existing      已有锚点（可为 null）
     * @param respawnDim    当前复活点维度（未绑定传 null）
     * @param respawnX/Y/Z  当前复活点坐标（未绑定传 null）
     * @param nowMillis     现在
     * @return 应当使用的锚点；未绑定时返回 null（家未知）
     */
    public static Anchor decide(Anchor existing, String respawnDim,
                                Integer respawnX, Integer respawnY, Integer respawnZ,
                                long nowMillis) {
        if (existing != null) return existing;   // 已有家：不因换床而改
        if (respawnDim == null || respawnX == null || respawnY == null || respawnZ == null) return null;
        return new Anchor(respawnDim, respawnX, respawnY, respawnZ, nowMillis);
    }

    /** 复活点是否已经离开家（只用于陈述事实，不触发搬家）。 */
    public static boolean respawnMovedAway(Anchor anchor, Integer respawnX, Integer respawnY, Integer respawnZ, double radius) {
        if (anchor == null || respawnX == null || respawnY == null || respawnZ == null) return false;
        double d = Math.sqrt(Math.pow(anchor.x() - respawnX, 2)
                + Math.pow(anchor.y() - respawnY, 2) + Math.pow(anchor.z() - respawnZ, 2));
        return d > radius;
    }
}