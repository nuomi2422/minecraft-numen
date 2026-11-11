package com.dwinovo.numen.rdd.side;

/** Trigger: only critical hunger/health; normal "no food while full" must not steal the body. */
public final class FoodSupplySideTaskTrigger implements SideTaskTrigger {
    private final java.util.function.Predicate<Context> canStart;

    public FoodSupplySideTaskTrigger() {
        this(companionId -> true);
    }

    /**
     * 2026-11-11: reuse the host cooldown at the actual automatic admission point.
     * Keeping its clock/state in the host avoids a second cooldown ledger in the engine.
     */
    public FoodSupplySideTaskTrigger(java.util.function.Predicate<Context> canStart) {
        this.canStart = java.util.Objects.requireNonNull(canStart, "canStart");
    }

    @Override public String typeId() { return FoodSupplySideTaskType.TYPE_ID; }

    @Override public boolean fires(Context ctx) {
        if (ctx == null || !canStart.test(ctx)) return false;
        return FoodSupplyPolicy.needsSupply(ctx.hunger(), ctx.health(), ctx.edibleCount());
    }

    @Override public boolean allowedWhileDeathOccupying() { return true; }

    @Override public boolean oncePerGameDay() { return false; }
}
