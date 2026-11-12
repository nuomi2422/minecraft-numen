package com.dwinovo.numen.plugins.camera.possess;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.dwinovo.numen.plugins.camera.possess.FollowRefresh.Action.*;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Dynamic tests of the exact pure decision consumed by production rebindAndFollow.
 * 2026-11-12: no Minecraft stubs; stable UUID can resolve to a new respawn entity.
 * Counterexample: after vanilla spectate co-location, each due call must still
 * request POSITION_PACKET; distance is deliberately not an eligibility input.
 * These decisions do not prove actual packets, ACKs, chunk meshes, or rendering.
 */
class FollowRefreshTest {
    private static final UUID OBSERVER = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID TARGET = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID OTHER = UUID.fromString("30000000-0000-0000-0000-000000000003");
    private static final FollowRefresh.Binding BOUND = new FollowRefresh.Binding(7, OBSERVER, TARGET);

    @Test void dueSamePositionStillRequestsPositionPacketEveryTime() {
        // No distance argument: server co-location cannot suppress the due packet.
        for (int due = 0; due < 10; due++) {
            assertEquals(POSITION_PACKET, FollowRefresh.plan(true, BOUND, BOUND, TARGET, true, true, true));
        }
    }

    @Test void crossDimensionMustChangeObserverDimensionBeforePositionPacket() {
        assertEquals(CHANGE_DIMENSION, FollowRefresh.plan(true, BOUND, BOUND, TARGET, true, true, false));
    }

    @Test void releasedBindingCannotRefresh() {
        assertEquals(NONE, FollowRefresh.plan(false, BOUND, BOUND, TARGET, true, true, true));
    }

    @Test void oldQueuedWorkCannotApplyAfterReleaseAndSameTargetRebind() {
        var rebound = new FollowRefresh.Binding(8, OBSERVER, TARGET);
        assertEquals(NONE, FollowRefresh.plan(true, BOUND, rebound, TARGET, true, true, true));
    }

    @Test void changedObserverOrTargetCannotReceiveOldRefresh() {
        assertEquals(NONE, FollowRefresh.plan(true, BOUND, new FollowRefresh.Binding(7, OTHER, TARGET), TARGET, true, true, true));
        assertEquals(NONE, FollowRefresh.plan(true, BOUND, new FollowRefresh.Binding(7, OBSERVER, OTHER), TARGET, true, true, true));
    }

    @Test void resolvedWrongUuidCannotSubstituteSameName() {
        assertEquals(NONE, FollowRefresh.plan(true, BOUND, BOUND, OTHER, true, true, true));
    }

    @Test void missingOrDeadTargetWaitsWithoutMovingObserver() {
        assertEquals(NONE, FollowRefresh.plan(true, BOUND, BOUND, null, true, true, true));
        assertEquals(NONE, FollowRefresh.plan(true, BOUND, BOUND, TARGET, true, false, true));
    }

    @Test void respawnedEntityWithOriginalUuidCanRefresh() {
        assertEquals(POSITION_PACKET, FollowRefresh.plan(true, BOUND, BOUND, UUID.fromString(TARGET.toString()), true, true, true));
    }

    @Test void nonSpectatorOrObserverAsTargetCannotMove() {
        assertEquals(NONE, FollowRefresh.plan(true, BOUND, BOUND, TARGET, false, true, true));
        var self = new FollowRefresh.Binding(7, OBSERVER, OBSERVER);
        assertEquals(NONE, FollowRefresh.plan(true, self, self, OBSERVER, true, true, true));
    }

    @Test void nullIdentityOrLostCurrentBindingIsNotEligible() {
        assertEquals(NONE, FollowRefresh.plan(true, null, BOUND, TARGET, true, true, true));
        assertEquals(NONE, FollowRefresh.plan(true, BOUND, null, TARGET, true, true, true));
        var missingObserver = new FollowRefresh.Binding(7, null, TARGET);
        var missingTarget = new FollowRefresh.Binding(7, OBSERVER, null);
        assertEquals(NONE, FollowRefresh.plan(true, missingObserver, missingObserver, TARGET, true, true, true));
        assertEquals(NONE, FollowRefresh.plan(true, missingTarget, missingTarget, TARGET, true, true, true));
    }
}
