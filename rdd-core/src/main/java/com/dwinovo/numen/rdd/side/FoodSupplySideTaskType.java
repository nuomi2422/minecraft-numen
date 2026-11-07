package com.dwinovo.numen.rdd.side;

import java.util.Map;

/** 2026-11-08: survival food supply side task; preempts mainline only at critical hunger/hp. */
public final class FoodSupplySideTaskType implements SideTaskType {

    public static final String TYPE_ID = "food_supply";

    @Override public String typeId() { return TYPE_ID; }

    @Override public long deadlineTicks() { return 20L * 60L * 10L; } // 10 min

    @Override public SideTask create(CreationContext ctx) {
        return new SideTask(ctx.companionId(), ctx.instanceId(), TYPE_ID, ctx.generation(),
                ctx.scheduledGameDay(), ctx.gameTime(), ctx.deadlineGameTime(),
                SideTaskState.ACTIVE, "SEEK_FOOD", ctx.data(), ctx.mainlineToken());
    }

    @Override public SideTaskVerdict evaluate(SideTask task, SideTaskWorldPort world) {
        if (task.pastDeadline(world.gameTime())) return SideTaskVerdict.skip("food supply ttl expired");
        int hunger = world.hunger(task.companionId());
        int edible = world.edibleCount(task.companionId());
        if (hunger >= 14 || edible >= 2) {
            return SideTaskVerdict.complete("food supply done: hunger=" + hunger + " edible=" + edible,
                    task.data());
        }
        return SideTaskVerdict.keep("SEEK_FOOD", task.data());
    }

    @Override public String renderContext(SideTask task, SideTaskWorldPort world) {
        int hunger = world.hunger(task.companionId());
        int hp = world.health(task.companionId());
        int edible = world.edibleCount(task.companionId());
        return "[survival] 找吃的优先于主线。当前 hunger=" + hunger + " hp=" + hp + " 可食物=" + edible
                + "。按现场条件选一条：①附近动物击杀并捡肉/熟肉；②村庄干草块/箱子找现成食物；"
                + "③水边钓鱼。拿到能吃的先吃，目标 hunger>=14 或可食物>=2；找不到就用 rdd_decline_side 说明。";
    }
}
