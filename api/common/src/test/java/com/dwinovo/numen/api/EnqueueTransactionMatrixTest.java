package com.dwinovo.numen.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** R-08: post-execution evaluation over separate server samples (no client mirror in this decision). */
class EnqueueTransactionMatrixTest {

    private static EnqueueScheduling.ServerSample sample(String world, String session, long epoch, Object entity) {
        return new EnqueueScheduling.ServerSample(world, session, epoch, new Object(), entity);
    }

    private static String decide(EnqueueScheduling.ServerSample before, EnqueueScheduling.ServerSample after) {
        var executed = new NumenGateway.Executed(Delivery.SEEN, "cmd-1");
        return NumenGateway.evaluate(UUID.randomUUID(), before, after, executed).identityStatus().name();
    }

    @Test
    void missingBeforeIsNoIdentity() {
        var before = sample(null, null, 0L, null);
        var after = sample("w", "s", 7, new Object());
        assertEquals("NO_IDENTITY", decide(before, after));
    }

    @Test
    void sameEpochDifferentSessionIsAttemptedButInvalid() {
        var before = sample("w", "s1", 7, new Object());
        var after = sample("w", "s2", 7, before.entityRef());
        assertEquals("ATTEMPTED_BUT_INVALID", decide(before, after));
    }

    @Test
    void differentServerRefIsAttemptedButInvalid() {
        var entity = new Object();
        var before = sample("w", "s", 7, entity);
        var after = new EnqueueScheduling.ServerSample("w", "s", 7, new Object(), entity);
        assertEquals("ATTEMPTED_BUT_INVALID", decide(before, after));
    }

    @Test
    void missingAfterIsAttemptedButInvalid() {
        var before = sample("w", "s", 7, new Object());
        assertEquals("ATTEMPTED_BUT_INVALID", decide(before, null));
    }

    @Test
    void identicalSamplesAreOk() {
        var entity = new Object();
        var before = sample("w", "s", 7, entity);
        var after = new EnqueueScheduling.ServerSample("w", "s", 7, before.serverRef(), entity);
        assertEquals("OK", decide(before, after));
    }

    @Test
    void twoIndependentTransactionsDoNotShareState() {
        var a = sample("wa", "sa", 1, new Object());
        var b = sample("wb", "sb", 2, new Object());
        assertEquals("OK", decide(a, a));
        assertEquals("OK", decide(b, b));
        assertEquals("ATTEMPTED_BUT_INVALID", decide(a, b));
    }
}
