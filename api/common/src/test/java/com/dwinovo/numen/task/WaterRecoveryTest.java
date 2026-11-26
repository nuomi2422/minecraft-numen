package com.dwinovo.numen.task;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class WaterRecoveryTest {
    private final UUID id = UUID.randomUUID();
    private final BlockPos origin = new BlockPos(10, 64, 20);

    @AfterEach
    void clear() {
        WaterRecovery.clear(id);
    }

    @Test
    void duplicateRequestKeepsTheOriginalFingerprintAndBudget() {
        WaterRecovery.request(id, origin, 100);
        WaterRecovery.request(id, new BlockPos(90, 70, 90), 200);

        assertEquals(origin, WaterRecovery.begin(id, 200));
        assertNull(WaterRecovery.begin(id, 201));
        assertEquals(WaterRecovery.State.RECOVERING, WaterRecovery.poll(id, 201));
    }

    @Test
    void successCreatesALocalTemporaryAvoidanceZone() {
        WaterRecovery.request(id, origin, 100);
        WaterRecovery.begin(id, 100);
        assertTrue(WaterRecovery.succeed(id, 120));

        var zones = WaterRecovery.avoidedZones(id, 120);
        assertEquals(1, zones.size());
        assertTrue(zones.get(0).contains(14, 66, 24));
        assertTrue(zones.get(0).contains(10, 64, 20));
        assertFalse(zones.get(0).contains(15, 64, 20));
        assertFalse(zones.get(0).contains(10, 67, 20));
        assertEquals(WaterRecovery.State.SUCCEEDED, WaterRecovery.poll(id, 121));
        assertEquals(WaterRecovery.State.NONE, WaterRecovery.poll(id, 122));
    }

    @Test
    void avoidanceAllowsOnlyOutwardMovesWhenTheBodyIsAlreadyInside() {
        var zone = new WaterRecovery.AvoidZone(origin, 200);
        assertFalse(zone.allowsExit(10, 64, 20, 10, 64, 20));
        assertFalse(zone.allowsExit(11, 64, 20, 10, 64, 20));
        assertTrue(zone.allowsExit(10, 64, 20, 11, 64, 20));
        assertTrue(zone.allowsExit(10, 64, 20, 10, 66, 20));
    }

    @Test
    void failedEscapeStillBlocksTheStuckWaterForTheNextRoute() {
        WaterRecovery.request(id, origin, 100);
        WaterRecovery.begin(id, 100);
        assertTrue(WaterRecovery.fail(id, 130));

        assertEquals(WaterRecovery.State.FAILED, WaterRecovery.poll(id, 131));
        assertTrue(WaterRecovery.avoidedZones(id, 131).get(0).contains(origin.getX(), origin.getY(), origin.getZ()));
    }

    @Test
    void requestedStateDrivesMineInterruptionWithoutConsumingTheOutcome() {
        assertFalse(WaterRecovery.requested(id));
        WaterRecovery.request(id, origin, 100);
        assertTrue(WaterRecovery.requested(id));
        WaterRecovery.begin(id, 100);
        assertTrue(WaterRecovery.requested(id));
        WaterRecovery.succeed(id, 100);
        assertFalse(WaterRecovery.requested(id));
        assertEquals(WaterRecovery.State.SUCCEEDED, WaterRecovery.poll(id, 101));
    }

    @Test
    void recoveryBudgetStartsWhenNavigationActuallyBegins() {
        WaterRecovery.request(id, origin, 100);
        WaterRecovery.begin(id, 200);

        assertEquals(WaterRecovery.State.RECOVERING,
                WaterRecovery.poll(id, 200 + WaterRecovery.RECOVERY_BUDGET_TICKS - 1));
        assertEquals(WaterRecovery.State.FAILED, WaterRecovery.poll(id, 200 + WaterRecovery.RECOVERY_BUDGET_TICKS));
    }

    @Test
    void expiredBeginLeavesAFailureOutcomeForTheSupervisor() {
        WaterRecovery.request(id, origin, 100);

        assertNull(WaterRecovery.begin(id, 100 + WaterRecovery.RECOVERY_BUDGET_TICKS));
        assertEquals(WaterRecovery.State.FAILED, WaterRecovery.poll(id, 400));
        assertEquals(1, WaterRecovery.avoidedZones(id, 400).size());
    }

    @Test
    void expiredPendingRequestFallsBackToAvoidanceInsteadOfWaitingForever() {
        WaterRecovery.request(id, origin, 100);

        assertEquals(WaterRecovery.State.FAILED,
                WaterRecovery.poll(id, 100 + WaterRecovery.RECOVERY_BUDGET_TICKS));
        assertEquals(1, WaterRecovery.avoidedZones(id, 400).size());
    }

    @Test
    void avoidanceExpiresAndIsIsolatedPerCompanion() {
        UUID other = UUID.randomUUID();
        try {
            WaterRecovery.request(id, origin, 100);
            WaterRecovery.begin(id, 100);
            WaterRecovery.succeed(id, 100);

            assertTrue(WaterRecovery.avoidedZones(other, 100).isEmpty());
            assertTrue(WaterRecovery.avoidedZones(id, 100 + WaterRecovery.AVOID_TTL_TICKS - 1).size() == 1);
            assertTrue(WaterRecovery.avoidedZones(id, 100 + WaterRecovery.AVOID_TTL_TICKS).isEmpty());
        } finally {
            WaterRecovery.clear(other);
        }
    }

    @Test
    void clearingARequestDoesNotRemoveUsefulRouteAvoidance() {
        WaterRecovery.request(id, origin, 100);
        WaterRecovery.begin(id, 100);
        WaterRecovery.succeed(id, 100);
        WaterRecovery.clearRequest(id);

        assertEquals(WaterRecovery.State.NONE, WaterRecovery.poll(id, 101));
        assertEquals(1, WaterRecovery.avoidedZones(id, 101).size());
    }
}
