package com.dwinovo.numen.plugins.rdd;

/**
 * 危险事实块的**纯文本**部分（无 Minecraft 依赖，可单测）。
 *
 * <p>为什么拆出来：`RddDangerFacts` 要读世界（Mob/Monster/AABB），
 * 测试类一加载它就 `NoClassDefFoundError`（插件测试不在 MC 运行期）。
 * 措辞与阈值恰恰是最该被钉住的部分，所以让它独立、可测。
 *
 * <p>证据来源（2026-10-27）：`exp-session-stats` 扫最近 3 天 52 次死亡，
 * 4 次死时正在 `rdd_get_inventory` / `task_status` / `rdd_whereami`，2 次死时正在 `eat`。
 */
final class RddDangerText {

    private RddDangerText() {}

    /** 快照新鲜度上限（tick）。tick 每轮采样，1 秒足够；超过就说明采样停了。 */
    static final int SNAPSHOT_TTL_TICKS = 20;

    /**
     * 读不到敌怪事实时的**显式未知**标记。
     *
     * <p>为什么要它：以前 `return ""` 同时表示"没有危险"和"读不到"，两者在上下文里长得一样。
     * 空串会让执行 AI 以为"安全"，而真相是"根本没看见"。宁可占几个 token，也不给假的安全感。
     */
    static String unknownMarker() {
        return "<danger>敌怪状态未知（没读到观测，tick 采样也没有数据）——这不等于安全</danger>";
    }

    /** 快照是否还新鲜（纯函数，便于单测）。 */
    static boolean isFresh(long nowTick, long snapshotTick, int ttlTicks) {
        if (snapshotTick < 0) return false;
        long age = nowTick - snapshotTick;
        return age >= 0 && age <= ttlTicks;
    }

    /** 8 格内算「贴身」，无论血量都会提示。 */
    static final int CLOSE_RADIUS = 8;
    /** 扫描半径（再远的怪与"我现在要不要紧"无关）。 */
    static final int SCAN_RADIUS = 16;
    /** 血量低于这个百分比才算「已经吃亏了」。 */
    static final int HURT_PERCENT = 70;

    /**
     * 给出危险块（无危险返回空串）。
     *
     * @param hostiles      扫描半径内敌怪数
     * @param closeHostiles 其中 8 格内的数量
     * @param healthPercent 血量百分比（0~100，越界夹住）
     * @param kinds         敌怪种类文本（可空，最多取前若干种，由调用方截断）
     */
    static String block(int hostiles, int closeHostiles, int healthPercent, String kinds) {
        if (hostiles <= 0) {
            return "";
        }
        boolean hurt = healthPercent < HURT_PERCENT;
        if (!hurt && closeHostiles <= 0) {
            return "";   // 远处怪 + 血还满 → 不是"此刻的麻烦"，不占 token
        }
        StringBuilder sb = new StringBuilder("<danger>");
        sb.append("附近 ").append(hostiles).append(" 只敌怪");
        if (closeHostiles > 0) {
            sb.append("（").append(closeHostiles).append(" 只在 8 格内）");
        }
        if (kinds != null && !kinds.isBlank()) {
            sb.append("：").append(kinds);
        }
        sb.append("；血量 ").append(Math.max(0, Math.min(100, healthPercent))).append("%");
        if (hurt) {
            sb.append("（已经受伤）");
        }
        sb.append("。这是事实，不是指令：查背包/查任务状态不改变它们的距离，"
                + "先把眼前的威胁处理掉再看。");
        sb.append("</danger>");
        return sb.toString();
    }
}
