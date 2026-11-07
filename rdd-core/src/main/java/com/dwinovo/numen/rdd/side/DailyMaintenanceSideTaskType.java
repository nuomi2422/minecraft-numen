package com.dwinovo.numen.rdd.side;

import java.util.Map;

/** 2026-11-08: executable daily side task (planner DAILY node -> real side task). */
public final class DailyMaintenanceSideTaskType implements SideTaskType {

    public static final String TYPE_ID = "daily_maintenance";

    @Override public String typeId() { return TYPE_ID; }

    @Override public long deadlineTicks() { return 20L * 60L * 10L; } // 10 min, then silent skip

    @Override public SideTask create(CreationContext ctx) {
        return new SideTask(ctx.companionId(), ctx.instanceId(), TYPE_ID, ctx.generation(),
                ctx.scheduledGameDay(), ctx.gameTime(), ctx.deadlineGameTime(),
                SideTaskState.ACTIVE, "todo", ctx.data(), ctx.mainlineToken());
    }

    @Override public SideTaskVerdict evaluate(SideTask task, SideTaskWorldPort world) {
        System.out.println("[rdd] daily evaluate id=" + task.instanceId() + " dataKeys=" + task.data().keySet());
        if (task.pastDeadline(world.gameTime())) return SideTaskVerdict.skip("daily ttl expired");
        String key = task.data().getOrDefault("asset_key", "");
        int minimum;
        try { minimum = Integer.parseInt(task.data().getOrDefault("minimum", "1")); }
        catch (RuntimeException e) { minimum = 1; }
        if (key.isBlank()) return SideTaskVerdict.skip("daily task missing asset_key");
        int held = world.countItem(task.companionId(), key);
        if (held >= minimum) return SideTaskVerdict.complete("daily condition met: " + key + " " + held + "/" + minimum);
        return SideTaskVerdict.keep("todo", Map.of());
    }

    @Override public String renderContext(SideTask task, SideTaskWorldPort world) {
        String desc = task.data().getOrDefault("description", "daily maintenance");
        String key = task.data().getOrDefault("asset_key", "");
        String min = task.data().getOrDefault("minimum", "1");
        return "[daily] " + desc + "（完成条件：" + key + " ≥ " + min + "；可延后，主线恢复即让位）";
    }
}
