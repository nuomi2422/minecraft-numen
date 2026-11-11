package com.dwinovo.numen.rdd.side;

/** Pure survival gates: critical hunger/hp, and post-completion cooldown. -1 = UNKNOWN. */
public final class FoodSupplyPolicy {
    public static final long COOLDOWN_MS = 5L * 60L * 1000L;
    private FoodSupplyPolicy() {}

    public static boolean critical(int hunger, int health, int edible) {
        if (health > 0 && health <= 8) return true;
        if (health == 0) return false; // dead: death recovery owns it
        return hunger >= 0 && ((hunger <= 6 && edible == 0) || hunger <= 3);
    }

    /** Reuse the existing supply completion stock (two edible items); eating is separate. */
    public static boolean needsSupply(int hunger, int health, int edible) {
        return edible < 2 && critical(hunger, health, edible);
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
