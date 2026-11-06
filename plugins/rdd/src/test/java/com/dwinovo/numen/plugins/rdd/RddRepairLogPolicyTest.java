package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RddRepairLogPolicyTest {

    @Test void sameSeqMustNotRecomputeOrLog() {
        assertTrue(RddRepairLogPolicy.skipSameBatch(64L, 64L));
        assertFalse(RddRepairLogPolicy.skipSameBatch(64L, 65L));
        assertFalse(RddRepairLogPolicy.skipSameBatch(Integer.MIN_VALUE, 64L));
    }

    @Test void armedLogsOnlyOncePerCompanion() {
        Set<UUID> armed = ConcurrentHashMap.newKeySet();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        assertTrue(RddRepairLogPolicy.shouldLogArmed(armed, a));
        assertFalse(RddRepairLogPolicy.shouldLogArmed(armed, a));
        assertTrue(RddRepairLogPolicy.shouldLogArmed(armed, b));
        assertFalse(RddRepairLogPolicy.shouldLogArmed(armed, null));
    }
}
