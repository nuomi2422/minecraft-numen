package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

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

    // 2026-11-24: B advisory wake route. The real SideTaskHost helper is called by reflection
    // so this test compiles before the host-side helper lands; it is RED until then.
    @Test
    void advisoryEventUsesTheSharedRepeatWindowWithAValidServerIdentity() throws Exception {
        Map<UUID, Long> last = new HashMap<>();
        AtomicInteger sends = new AtomicInteger();
        assertTrue(nudgeAdvisory("side_task_advisory", data(A, "world-A", "session-A", 1000L), last, "world-A", "session-A",
                () -> { sends.incrementAndGet(); return true; }), "first B advisory must wake once");
        assertEquals(1, sends.get());
        assertEquals(Long.valueOf(1000L), last.get(A));

        assertFalse(nudgeAdvisory("side_task_advisory", data(A, "world-A", "session-A", 5799L), last, "world-A", "session-A",
                () -> { sends.incrementAndGet(); return true; }), "same repeat window must suppress the next B wake");
        assertEquals(1, sends.get());

        assertTrue(nudgeAdvisory("side_task_advisory", data(A, "world-A", "session-A", 5800L), last, "world-A", "session-A",
                () -> { sends.incrementAndGet(); return true; }), "after the window a new B advisory may wake again");
        assertEquals(2, sends.get());
        assertEquals(Long.valueOf(5800L), last.get(A));
    }

    @Test
    void advisoryRejectedDeliveryDoesNotConsumeTheWindow() throws Exception {
        Map<UUID, Long> last = new HashMap<>();
        assertFalse(nudgeAdvisory("side_task_advisory", data(A, "world-A", "session-A", 2000L), last, "world-A", "session-A",
                () -> false), "a rejected send must report false");
        assertTrue(last.isEmpty(), "a rejected B send must not record a successful-reminder time");

        AtomicInteger sends = new AtomicInteger();
        assertTrue(nudgeAdvisory("side_task_advisory", data(A, "world-A", "session-A", 2001L), last, "world-A", "session-A",
                () -> { sends.incrementAndGet(); return true; }), "a later accepted B send still wakes");
        assertEquals(1, sends.get());
    }

    @Test
    void advisoryRoutingRejectsOtherEventsMissingIdentityAndCrossServer() throws Exception {
        Map<UUID, Long> last = new HashMap<>();
        AtomicInteger sends = new AtomicInteger();
        BooleanSupplier send = () -> { sends.incrementAndGet(); return true; };

        assertFalse(nudgeAdvisory("side_task_deferred", data(A, "world-A", "session-A", 3000L), last, "world-A", "session-A", send));
        assertFalse(nudgeAdvisory("side_task_advisory", data(null, "world-A", "session-A", 3000L), last, "world-A", "session-A", send));
        assertFalse(nudgeAdvisory("side_task_advisory", data(A, "world-B", "session-A", 3000L), last, "world-A", "session-A", send));
        assertFalse(nudgeAdvisory("side_task_advisory", data(A, "world-A", "session-B", 3000L), last, "world-A", "session-A", send));
        assertFalse(nudgeAdvisory("side_task_advisory", data(A, "world-A", "session-A", 3000L), last, "world-A", "", send));
        assertEquals(0, sends.get(), "only a same-server side_task_advisory may route to the B wake");
        assertTrue(last.isEmpty());
    }

    @Test
    void advisoryRouteHasNoBodyStopOrClearPort() throws Exception {
        // The helper's only interaction is the BooleanSupplier receipt + the shared clock.
        // There is no stopActive/clearBody/clearNudgeThrottle port to call here by construction.
        Map<UUID, Long> last = new HashMap<>();
        assertTrue(nudgeAdvisory("side_task_advisory", data(A, "world-A", "session-A", 4000L), last, "world-A", "session-A",
                () -> true));
        assertEquals(Map.of(A, 4000L), last, "the route records only the shared successful-reminder clock");
    }

    private static Map<String, Object> data(UUID id, String world, String session, long gameTime) {
        Map<String, Object> data = new HashMap<>();
        if (id != null) data.put("companionId", id.toString());
        data.put("world_id", world);
        data.put("server_session", session);
        data.put("gameTime", gameTime);
        data.put("typeId", "sleep");
        return data;
    }

    private static boolean nudgeAdvisory(String event, Map<String, Object> data, Map<UUID, Long> last,
                                         String currentWorld, String currentSession, BooleanSupplier send) throws Exception {
        var m = SideTaskHost.class.getDeclaredMethod("nudgeAdvisoryEvent", String.class, Map.class, Map.class,
                String.class, String.class, BooleanSupplier.class);
        m.setAccessible(true);
        return (Boolean) m.invoke(null, event, data, last, currentWorld, currentSession, send);
    }
}
