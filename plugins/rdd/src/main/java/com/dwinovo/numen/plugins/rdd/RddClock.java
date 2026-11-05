package com.dwinovo.numen.plugins.rdd;

import net.minecraft.server.level.ServerLevel;

/**
 * 全插件**唯一**的 tick 来源。
 *
 * <p>为什么要有它：2026-11-05 发生过"写入用 `getTickCount`、读取用 `getGameTime`"的事故——
 * 两个计数器不同源，重启后快照永远不新鲜。只靠"记得用同一个"不够，要有结构上的唯一入口。
 *
 * <p>另外两者语义不同，不能全用一个：
 * <ul>
 *   <li>`getTickCount()`：开服至今，重启归零——**tick 间隔、快照新鲜度**用它；</li>
 *   <li>`getGameTime()`：跟存档走，跨重启连续——**需要持久的计时**才用它（例如暂停判定）。</li>
 * </ul>
 * 关键纪律：**同一对写/读必须同源**，不是全仓只用一种。
 */
final class RddClock {

    private RddClock() {}

    /** 开服至今的 tick（重启归零）：tick 间隔/新鲜度用这个。 */
    static long current(ServerLevel level) {
        return level.getServer().getTickCount();
    }
}
