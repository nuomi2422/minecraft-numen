package com.dwinovo.numen.entity;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RespawnEventSnapshotTest {
    private static final RespawnEventSnapshot.Landing CANDIDATE =
            new RespawnEventSnapshot.Landing("minecraft:overworld", 156.5, 76, 85.5);

    @Test void spawnCallbackBedRelocationReportsReturnedBodyNotCandidate() {
        var actual = new RespawnEventSnapshot.Landing("minecraft:overworld", 6176.5, 83, 6177.5);
        var attrs = RespawnEventSnapshot.attributes(CANDIDATE, "owner_nearby", () -> actual);
        assertEquals(actual.pos(), attrs.get("pos"));
        assertEquals("spawn_callback_relocated", attrs.get("reason"));
        assertEquals(CANDIDATE.pos(), attrs.get("candidate_pos"));
        assertEquals("owner_nearby", attrs.get("candidate_reason"));
    }

    @Test void callbackDimensionChangeReportsFinalDimensionWithoutGuessingBedSource() {
        var actual = new RespawnEventSnapshot.Landing("minecraft:the_end", 10.5, 70, -2.5);
        var attrs = RespawnEventSnapshot.attributes(CANDIDATE, "world_spawn_fallback", () -> actual);
        assertEquals(actual.dimension(), attrs.get("dimension"));
        assertEquals(actual.pos(), attrs.get("pos"));
        assertEquals("spawn_callback_relocated", attrs.get("reason"));
        assertEquals("world_spawn_fallback", attrs.get("candidate_reason"));
    }

    @Test void unchangedOwnerCandidateKeepsItsObservedSource() {
        var attrs = RespawnEventSnapshot.attributes(CANDIDATE, "owner_nearby", () -> CANDIDATE);
        assertEquals(CANDIDATE.pos(), attrs.get("pos"));
        assertEquals(CANDIDATE.dimension(), attrs.get("dimension"));
        assertEquals("owner_nearby", attrs.get("reason"));
    }

    @Test void unchangedWorldSpawnFallbackDoesNotClaimOwnerNearby() {
        var attrs = RespawnEventSnapshot.attributes(CANDIDATE, "world_spawn_fallback", () -> CANDIDATE);
        assertEquals("world_spawn_fallback", attrs.get("reason"));
    }

    @Test void finalBodyIsSampledOnceAtEmissionNotFromAnEarlierCandidate() {
        var calls = new AtomicInteger();
        RespawnEventSnapshot.attributes(CANDIDATE, "owner_nearby", () -> {
            calls.incrementAndGet();
            return CANDIDATE;
        });
        assertEquals(1, calls.get());
    }
}
