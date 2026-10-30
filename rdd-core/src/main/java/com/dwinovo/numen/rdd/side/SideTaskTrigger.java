package com.dwinovo.numen.rdd.side;

import java.util.UUID;

/**
 * 一类支线的触发器：判断"现在该不该开一条这种支线"。
 *
 * <p>去重（同一游戏日只开一次）与"已有活跃支线不再开"由 {@link SideTaskEngine} 统一负责，
 * 触发器只做纯粹的窗口判断（用游戏 tick，不用墙钟）。
 */
public interface SideTaskTrigger {

    String typeId();

    boolean fires(Context ctx);

    /**
     * 是否允许在旧死亡占用标志存在时启动。
     * 普通支线默认不抢；死亡恢复支线自身必须能在这个窗口里成为真正的驾驶任务。
     */
    default boolean allowedWhileDeathOccupying() {
        return false;
    }

    record Context(UUID companionId, String worldId, long gameDay, long dayTime, boolean hasDayNightCycle) {
    }
}
