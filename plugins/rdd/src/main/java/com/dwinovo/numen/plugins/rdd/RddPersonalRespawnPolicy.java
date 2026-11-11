package com.dwinovo.numen.plugins.rdd;

/**
 * Local personal-ground routing, independent of Minecraft for offline tests.
 * Why (2026-11-11): vanilla forced /spawnpoint was ignored by the bed-only consumer.
 * Use from RddPlugin SPAWN after taking the old bed anchor, before its fallback.
 * A later live bed overrides ground; unbound/non-forced points keep the old path.
 * No new stored anchor: vanilla player data remains the sole binding authority.
 */
final class RddPersonalRespawnPolicy {
    private RddPersonalRespawnPolicy() {}

    static boolean useGround(boolean hasPersonalPosition, boolean forced, boolean liveBed) {
        return hasPersonalPosition && forced && !liveBed;
    }
}
