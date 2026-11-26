package com.dwinovo.numen.api.observation;

/** Pure conversion of game ticks to the tick within a 24,000-tick day. */
public final class WorldTime {
    private WorldTime() {}

    public static long inDay(long gameTicks) {
        return Math.floorMod(gameTicks, 24_000L);
    }
}
