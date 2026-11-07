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

    public static boolean cooldownActive(long nowMs, long lastDoneMs) {
        return lastDoneMs > 0 && nowMs - lastDoneMs < COOLDOWN_MS;
    }
}
