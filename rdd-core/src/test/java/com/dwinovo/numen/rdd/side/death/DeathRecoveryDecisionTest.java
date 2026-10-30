package com.dwinovo.numen.rdd.side.death;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DeathRecoveryDecisionTest {
    @Test
    void onlyFixedStrategiesAreAccepted() {
        for (DeathRecoveryDecision d : DeathRecoveryDecision.values()) {
            assertEquals(d, DeathRecoveryDecision.parse(d.name()));
            assertEquals(d, DeathRecoveryDecision.parse(d.name().toLowerCase()));
        }
    }

    @Test
    void unknownStrategyIsRejected() {
        assertNull(DeathRecoveryDecision.parse("DELETE_RECOVERY"));
        assertNull(DeathRecoveryDecision.parse(""));
        assertNull(DeathRecoveryDecision.parse(null));
    }
}
