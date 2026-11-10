package com.dwinovo.numen.api;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * R-06 transaction matrix: same inputs through the OLD decision logic (copied, runnable)
 * and the NEW seam, so the fix is shown as old-red vs new-green without compiling the old tree.
 */
class EnqueueTransactionMatrixTest {

    private static EnqueueScheduling.Sample sample(String world, String session, long epoch,
                                                    String conn, Object entity) {
        return new EnqueueScheduling.Sample(world, session, epoch, conn, entity, entity, entity);
    }

    /** Old logic at 5e6b67665 (epoch-only, missing-before passes), copied here to stay runnable. */
    private static String oldDecide(EnqueueScheduling.Sample before, EnqueueScheduling.Sample after,
                                    UUID targetNow, UUID requested) {
        if (after == null || !after.hasIdentity()) return "NO_IDENTITY";
        if (before != null && before.hasIdentity() && before.epoch() != after.epoch()) return "INSTANCE_CHANGED";
        if (targetNow == null || !targetNow.equals(requested)) return "TARGET_CHANGED";
        return "OK";
    }

    private static String newDecide(EnqueueScheduling.Sample before, EnqueueScheduling.Sample after,
                                    UUID targetNow, UUID requested) {
        var executed = new NumenGateway.Executed(Delivery.SEEN, "cmd-1");
        return NumenGateway.evaluate(requested, before, after, executed, targetNow).identityStatus().name();
    }

    @Test
    void missingBeforeCannotBeFilledByAfter() {
        UUID c = UUID.randomUUID();
        var beforeMissing = sample(null, null, 0L, null, new Object());
        var after = sample("w", "s", 7, "c1", new Object());
        assertEquals("OK", oldDecide(beforeMissing, after, c, c));
        assertEquals("NO_IDENTITY", newDecide(beforeMissing, after, c, c));
    }

    @Test
    void sameEpochDifferentSessionIsRejected() {
        UUID c = UUID.randomUUID();
        var before = sample("w", "s1", 7, "c1", new Object());
        var after = sample("w", "s2", 7, "c1", new Object());
        assertEquals("OK", oldDecide(before, after, c, c));
        assertEquals("ATTEMPTED_BUT_INVALID", newDecide(before, after, c, c));
    }

    @Test
    void sameUuidNewEntityIsRejected() {
        UUID c = UUID.randomUUID();
        var before = sample("w", "s", 7, "c1", new Object());
        var after = sample("w", "s", 7, "c1", new Object());
        assertEquals("OK", oldDecide(before, after, c, c));
        assertEquals("ATTEMPTED_BUT_INVALID", newDecide(before, after, c, c));
    }

    @Test
    void identicalSubjectIsOk() {
        UUID c = UUID.randomUUID();
        var before = sample("w", "s", 7, "c1", new Object());
        assertEquals("OK", newDecide(before, before, c, c));
    }

    @Test
    void missingAfterIsNoIdentity() {
        UUID c = UUID.randomUUID();
        var before = sample("w", "s", 7, "c1", new Object());
        var after = sample(null, null, 0L, null, null);
        assertEquals("NO_IDENTITY", newDecide(before, after, c, c));
    }

    @Test
    void distinctCallsDoNotShareState() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var sa = sample("wa", "sa", 1, "ca", new Object());
        var sb = sample("wb", "sb", 2, "cb", new Object());
        assertEquals("OK", newDecide(sa, sa, a, a));
        assertEquals("OK", newDecide(sb, sb, b, b));
        assertEquals("ATTEMPTED_BUT_INVALID", newDecide(sa, sa, b, a));
    }
}
