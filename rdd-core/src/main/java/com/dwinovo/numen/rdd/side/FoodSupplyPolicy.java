package com.dwinovo.numen.rdd.side;

/** Pure survival gates: critical hunger/hp, and post-completion cooldown. -1 = UNKNOWN. */
public final class FoodSupplyPolicy {
    public static final long COOLDOWN_MS = 5L * 60L * 1000L;
    private static final int CRITICAL_HEALTH = 8;
    private static final int SUPPLY_STOCK = 2;
    private static final int SUFFICIENT_HUNGER = 14;
    private FoodSupplyPolicy() {}

    public static boolean critical(int hunger, int health, int edible) {
        if (health > 0 && health <= CRITICAL_HEALTH) return true;
        if (health == 0) return false; // dead: death recovery owns it
        return hunger >= 0 && ((hunger <= 6 && edible == 0) || hunger <= 3);
    }

    /** Reuse the existing supply completion stock (two edible items); eating is separate. */
    public static boolean needsSupply(int hunger, int health, int edible) {
        return edible < SUPPLY_STOCK && critical(hunger, health, edible);
    }

    /**
     * 2026-11-14: V13's low-hp task completed at hunger 15 without replenishing food.
     * Reuse the admission health/stock boundaries; UNKNOWN health cannot prove recovery.
     * Two confirmed edible items finish supply independently of eating or healing.
     */
    public static boolean supplyComplete(int hunger, int health, int edible) {
        return edible >= SUPPLY_STOCK
                || (hunger >= SUFFICIENT_HUNGER && health > CRITICAL_HEALTH);
    }

    /**
     * 2026-11-11: a new critical food deficit must not wait out an old low-hp cooldown.
     * Unknown inventory/hunger cannot prove that deficit; dead bodies still yield to recovery.
     */
    public static boolean cooldownBlocks(boolean coolingDown, int hunger, int health, int edible) {
        boolean urgentWithoutFood = health != 0 && edible == 0 && critical(hunger, -1, edible);
        return coolingDown && !urgentWithoutFood;
    }

    public static boolean cooldownActive(long nowMs, long lastDoneMs) {
        return lastDoneMs > 0 && nowMs - lastDoneMs < COOLDOWN_MS;
    }
}
