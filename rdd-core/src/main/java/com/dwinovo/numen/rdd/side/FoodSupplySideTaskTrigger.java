package com.dwinovo.numen.rdd.side;

/** Trigger: only critical hunger/health; normal "no food while full" must not steal the body. */
public final class FoodSupplySideTaskTrigger implements SideTaskTrigger {
    @Override public String typeId() { return FoodSupplySideTaskType.TYPE_ID; }

    @Override public boolean fires(Context ctx) {
        if (ctx == null) return false;
        return FoodSupplyPolicy.critical(ctx.hunger(), ctx.health(), ctx.edibleCount());
    }

    @Override public boolean allowedWhileDeathOccupying() { return true; }

    @Override public boolean oncePerGameDay() { return false; }
}
