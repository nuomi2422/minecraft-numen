package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.side.SideTask;
import com.dwinovo.numen.rdd.side.SideTaskDecision;
import com.dwinovo.numen.rdd.side.SideTaskType;
import com.dwinovo.numen.rdd.side.SideTaskVerdict;
import com.dwinovo.numen.rdd.side.SideTaskWorldPort;
import com.dwinovo.numen.rdd.side.SideTaskWorldPort.Wake;

import java.util.Map;
import java.util.UUID;

/**
 * 学习者派的支线（2026-10-27，权利缺口②的最小实现）。
 *
 * <p>完成口径：**条件窗口过去**（is_dark=false 的那条支线 = 天亮）。nudge 文本来自
 * 学习者草稿（renderContext / SideTaskHost 的拍醒都会用它）。到 deadline 条件还在
 * → SKIPPED_TIMEOUT（不是失败——学习者给的提示可能本来就不可执行）。
 *
 * <p>纯 JVM：只依赖 {@link SideTaskWorldPort}。
 */
final class LearnerSideTaskType implements SideTaskType {

    /** 提示窗口：触发后最多陪跑 10 分钟（与 sleep 支线同款保护）。 */
    static final long DEADLINE_TICKS = 12_000L;

    private final String id;
    private final String nudge;

    LearnerSideTaskType(String id, String nudge) {
        this.id = id;
        this.nudge = nudge;
    }

    @Override
    public String typeId() {
        return id;
    }

    @Override
    public long deadlineTicks() {
        return DEADLINE_TICKS;
    }

    @Override
    public SideTask create(CreationContext ctx) {
        return new SideTask(ctx.companionId(), ctx.instanceId(), id, ctx.generation(),
                ctx.scheduledGameDay(), ctx.gameTime(), ctx.deadlineGameTime(),
                // 状态直接 ACTIVE（引擎已判定触发）；phase 记 nudge 供宿主拍醒时直读
                com.dwinovo.numen.rdd.side.SideTaskState.ACTIVE, nudge, Map.of(), ctx.mainlineToken());
    }

    @Override
    public SideTaskVerdict evaluate(SideTask task, SideTaskWorldPort world) {
        // is_dark 型：天亮即完成（窗口过去 = 学习者要提醒的时段结束了）
        long t = Math.floorMod(world.dayTime(), 24_000L);
        boolean dark = t >= 13_000L && t < 23_000L;
        if (!dark) {
            return SideTaskVerdict.complete("窗口结束（天亮）");
        }
        // 睡着了也算完成（nudge 的目的通常就是把人送去睡）
        if (world.isSleeping(task.companionId())) {
            return SideTaskVerdict.complete("已入睡");
        }
        return SideTaskVerdict.keep("ACTIVE", java.util.Map.of());
    }

    @Override
    public SideTaskDecision decide(UUID companionId, SideTaskWorldPort world) {
        // 身体被占（正在跑别的）推迟到安全停顿点；战斗也推迟（同 sleep 支线纪律）
        if (world.inCombat(companionId)) {
            return SideTaskDecision.defer(200, "combat");
        }
        if (world.bodyBusy(companionId)) {
            return SideTaskDecision.defer(100, "body busy");
        }
        return SideTaskDecision.EXECUTE;
    }

    @Override
    public String renderContext(SideTask task, SideTaskWorldPort world) {
        return "<side_task type=\"" + id + "\">" + nudge + "</side_task>";
    }
}
