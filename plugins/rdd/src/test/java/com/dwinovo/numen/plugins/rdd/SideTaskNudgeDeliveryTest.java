package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-14: receipt-based tests for the actual SideTaskHost delivery seam.
 * V16 exposed a rejected reminder consuming the host's repeat window.
 * Run only this class through the managed plugins:rdd TestOnly route.
 * Boolean suppliers are the send receipt port, not a second RddPlugin or fake Minecraft host.
 * These tests do not prove global 180-second admission, side-state priority, or game delivery.
 */
class SideTaskNudgeDeliveryTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void rejectedSendDoesNotRecordASuccessfulReminder() {
        Map<UUID, Long> last = new HashMap<>();
        AtomicInteger sends = new AtomicInteger();

        SideTaskHost.NudgeDelivery.sendIfNeeded(A, last, false, true, 1000L, () -> {
            sends.incrementAndGet();
            return false;
        });

        assertEquals(1, sends.get(), "the real send port was attempted once");
        assertFalse(last.containsKey(A), "a false receipt must not consume the successful-reminder clock");
    }

    @Test
    void rejectedSendCanRetryWhenReadyBeforeTheLocalRepeatWindow() {
        Map<UUID, Long> last = new HashMap<>();
        AtomicInteger sends = new AtomicInteger();
        AtomicInteger delivered = new AtomicInteger();
        SideTaskHost.NudgeDelivery.sendIfNeeded(A, last, false, true, 1000L, () -> {
            sends.incrementAndGet();
            return false;
        });

        // The send port is now ready. This is not a simulation of the global RDD throttle.
        // 180 seconds at 20 ticks/second is still below the existing local 4800-tick repeat.
        SideTaskHost.NudgeDelivery.sendIfNeeded(A, last, true, true, 4600L, () -> {
            sends.incrementAndGet();
            delivered.incrementAndGet();
            return true;
        });

        assertAll(() -> assertEquals(2, sends.get(), "a rejection must not suppress the later ready send"),
                () -> assertEquals(1, delivered.get()),
                () -> assertEquals(Long.valueOf(4600L), last.get(A)));
    }

    @Test
    void successfulSendKeepsTheOriginalFourMinuteRepeatBoundary() {
        Map<UUID, Long> last = new HashMap<>();
        AtomicInteger sends = new AtomicInteger();
        SideTaskHost.NudgeDelivery.sendIfNeeded(A, last, false, true, 1000L, () -> {
            sends.incrementAndGet();
            return true;
        });
        SideTaskHost.NudgeDelivery.sendIfNeeded(A, last, true, true, 5799L, () -> {
            fail("the existing local repeat boundary must still suppress this send");
            return true;
        });
        assertEquals(Long.valueOf(1000L), last.get(A));

        SideTaskHost.NudgeDelivery.sendIfNeeded(A, last, true, true, 5800L, () -> {
            sends.incrementAndGet();
            return true;
        });

        assertEquals(2, sends.get());
        assertEquals(Long.valueOf(5800L), last.get(A));
    }

    @Test
    void inactiveStateClearsOnlyItsOwnClockWithoutSending() {
        Map<UUID, Long> last = new HashMap<>();
        last.put(A, 1000L);
        last.put(B, 2000L);

        SideTaskHost.NudgeDelivery.sendIfNeeded(A, last, true, false, 0L, () -> {
            fail("inactive state must return before the send port");
            return true;
        });

        assertFalse(last.containsKey(A));
        assertEquals(Map.of(B, 2000L), last);
    }

    @Test
    void anotherCompanionDoesNotShareTheSuccessfulReminderClock() {
        Map<UUID, Long> last = new HashMap<>();
        AtomicInteger aSends = new AtomicInteger();
        AtomicInteger bSends = new AtomicInteger();
        SideTaskHost.NudgeDelivery.sendIfNeeded(A, last, false, true, 1000L, () -> {
            aSends.incrementAndGet();
            return true;
        });
        SideTaskHost.NudgeDelivery.sendIfNeeded(B, last, true, true, 1020L, () -> {
            bSends.incrementAndGet();
            return true;
        });
        SideTaskHost.NudgeDelivery.sendIfNeeded(A, last, true, true, 1040L, () -> {
            fail("same active companion must retain its successful reminder window");
            return true;
        });

        assertAll(() -> assertEquals(1, aSends.get()), () -> assertEquals(1, bSends.get()),
                () -> assertEquals(Map.of(A, 1000L, B, 1020L), last));
    }

    @Test
    void exceptionalSendDoesNotRecordASuccessfulReminder() {
        Map<UUID, Long> last = new HashMap<>();
        IllegalStateException failure = new IllegalStateException("send failed");

        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> SideTaskHost.NudgeDelivery.sendIfNeeded(A, last, false, true, 1000L, () -> {
                    throw failure;
                })));
        assertTrue(last.isEmpty());
    }

    @Test
    void rejectedRepeatPreservesThePreviousSuccessfulTime() {
        Map<UUID, Long> last = new HashMap<>();
        last.put(A, 1000L);
        AtomicInteger sends = new AtomicInteger();

        SideTaskHost.NudgeDelivery.sendIfNeeded(A, last, true, true, 5800L, () -> {
            sends.incrementAndGet();
            return false;
        });

        assertEquals(1, sends.get(), "the previous repeat interval has elapsed");
        assertEquals(Long.valueOf(1000L), last.get(A), "a rejection must preserve the prior success");
    }

    @Test
    void failedAttemptsThenOneSuccessStartOnlyOneRepeatWindow() {
        Map<UUID, Long> last = new HashMap<>();
        AtomicInteger sends = new AtomicInteger();
        for (long now : new long[]{1000L, 1020L, 1040L}) {
            SideTaskHost.NudgeDelivery.sendIfNeeded(A, last, true, true, now,
                    () -> sends.incrementAndGet() == 3);
        }
        assertEquals(3, sends.get());
        assertEquals(Long.valueOf(1040L), last.get(A));

        SideTaskHost.NudgeDelivery.sendIfNeeded(A, last, true, true, 1060L, () -> {
            fail("the accepted reminder must suppress another send in its repeat window");
            return true;
        });
        assertEquals(3, sends.get());
        assertEquals(Long.valueOf(1040L), last.get(A));
    }
}
