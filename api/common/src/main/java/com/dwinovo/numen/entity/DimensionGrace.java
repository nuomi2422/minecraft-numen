package com.dwinovo.numen.entity;

/**
 * 2026-11-02（B7 实机）：换维度后的保护期判定。
 *
 * <p>进下界 1 分钟内站回门时，D-20 的假玩家传送兜底会把人送回主世界；
 * 换维度后 {@link #GRACE_TICKS} 内兜底不累计、不传送，给「先站定/铺台」留窗口。
 *
 * <p>纯 JVM 类（无 Minecraft 依赖），可直接单测。
 */
final class DimensionGrace {

    /**
     * 2026-11-02 实机修正：原 100 tick 保护期 + D-20 兜底 100 tick = 200 tick，
     * 超过 use_portal 的 180 tick 等待，回执变成「standing inside 180 ticks and it did not move me」。
     * 收到 40 tick（2 秒）：足够阻止「进界瞬间被弹回」，又不拖过工具的等待窗口。
     */
    static final int GRACE_TICKS = 40;

    private DimensionGrace() {}

    static boolean inGrace(long nowTick, long changedAtTick) {
        return changedAtTick != Long.MIN_VALUE && nowTick - changedAtTick < GRACE_TICKS;
    }
}