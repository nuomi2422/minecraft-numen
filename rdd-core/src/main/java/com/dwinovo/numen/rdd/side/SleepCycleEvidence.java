package com.dwinovo.numen.rdd.side;

import java.util.UUID;

/** Host-observed, owner-scoped sleep cycle. DAYBREAK means night entry followed by a daytime
 * wake, not proof of vanilla's natural skip-night cause. Unknown ports must not wrap bare Wake. */
public record SleepCycleEvidence(Status status, UUID companionId, String worldId, String serverSession,
                                 String dimension, String instanceId, long generation, long sequence,
                                 long startedGameTick, long observedGameTick, long startedDayTime, long observedDayTime) {
    public enum Status { UNKNOWN, OBSERVED_SLEEP, DAYBREAK, INTERRUPTED }
    public static final SleepCycleEvidence UNKNOWN = new SleepCycleEvidence(Status.UNKNOWN, null,
            "", "", "", "", -1, -1, -1, -1, -1, -1);
    public boolean matches(SideTask task) {
        return task != null && status != null && status != Status.UNKNOWN && companionId != null
                && companionId.equals(task.companionId()) && instanceId != null && instanceId.equals(task.instanceId())
                && generation == task.generation() && worldId != null && !worldId.isBlank()
                && serverSession != null && !serverSession.isBlank() && dimension != null && !dimension.isBlank()
                && sequence > 0 && startedGameTick >= task.createdAtGameTime() && observedGameTick >= startedGameTick
                && Math.floorMod(startedDayTime, 24_000L) >= 13_000L && Math.floorMod(startedDayTime, 24_000L) < 23_000L;
    }
}
