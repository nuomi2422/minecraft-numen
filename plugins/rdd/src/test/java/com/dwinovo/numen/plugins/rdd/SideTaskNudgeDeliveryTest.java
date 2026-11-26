package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-14: receipt-based tests for the actual SideTaskHost delivery seam.
 * V16 exposed a rejected reminder consuming the host's repeat window.
 * Run only this class through the managed plugins:rdd TestOnly route.
 * Boolean suppliers are the send receipt port, not a second RddPlugin or fake Minecraft host.
 * These tests do not prove global 180-second admission, side-state priority, or game delivery.
 */
class SideTaskNudgeDeliveryTest {
    private static Method isolatedNudgeMethod;
    private static URLClassLoader isolatedLoader;

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

    // 2026-11-24: B advisory wake route. The real helper and nested NudgeDelivery are extracted
    // from SideTaskHost source into an isolated consumer, so the MC-laden host class is never
    // loaded; the route is RED until the helper lands.
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
        try {
            return (Boolean) isolatedNudge().invoke(null, event, data, last, currentWorld, currentSession, send);
        } catch (InvocationTargetException e) {
            throw new AssertionError("SETUP: isolated real nudge route failed before business assertions", e.getCause());
        }
    }

    private static synchronized Method isolatedNudge() throws Exception {
        if (isolatedNudgeMethod != null) return isolatedNudgeMethod;
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("plugins/rdd/src/main/java"))) root = root.getParent();
        assertNotNull(root, "SETUP: repository root not found");
        String source = Files.readString(root.resolve(
                "plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/SideTaskHost.java"));
        String helper = isolatedMember(source, "nudgeAdvisoryEvent(");
        String delivery = isolatedMember(source, "final class NudgeDelivery {");
        Matcher repeat = Pattern.compile(
                "(?m)^\\s*(?:private|public|protected)?\\s*static\\s+final\\s+long\\s+NUDGE_REPEAT_TICKS\\s*=\\s*([^;]+);\\s*$")
                .matcher(source);
        assertTrue(repeat.find(), "SETUP: NUDGE_REPEAT_TICKS declaration missing");
        String unit = "package com.dwinovo.numen.plugins.rdd;\n"
                + "import java.util.*;\n"
                + "public final class SideTaskHostNudgeRoute {\n"
                + "    static final long NUDGE_REPEAT_TICKS = " + repeat.group(1) + ";\n"
                + helper
                + delivery
                + "}\n";
        Path out = Files.createTempDirectory("rdd-nudge-route-");
        Path sourceFile = out.resolve("com/dwinovo/numen/plugins/rdd/SideTaskHostNudgeRoute.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, unit);
        var diagnostics = new ByteArrayOutputStream();
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "SETUP: full JDK required");
        int status = compiler.run(null, diagnostics, diagnostics,
                "-encoding", "UTF-8", "-proc:none",
                "-classpath", System.getProperty("java.class.path"),
                "-d", out.toString(), sourceFile.toString());
        assertEquals(0, status, "SETUP: isolated real NudgeDelivery compilation failed:" + diagnostics);
        isolatedLoader = new URLClassLoader(new URL[]{out.toUri().toURL()},
                SideTaskNudgeDeliveryTest.class.getClassLoader());
        Class<?> route = isolatedLoader.loadClass("com.dwinovo.numen.plugins.rdd.SideTaskHostNudgeRoute");
        isolatedNudgeMethod = route.getDeclaredMethod("nudgeAdvisoryEvent", String.class, Map.class, Map.class,
                String.class, String.class, BooleanSupplier.class);
        isolatedNudgeMethod.setAccessible(true);
        return isolatedNudgeMethod;
    }

    /** Extract one real member (method or nested class) from the production source, never a copy. */
    private static String isolatedMember(String source, String marker) {
        int hit = source.indexOf(marker);
        assertTrue(hit >= 0, "SETUP: production member missing: " + marker);
        int start = source.lastIndexOf('\n', hit) + 1;
        int indent = 0;
        while (start + indent < source.length() && source.charAt(start + indent) == ' ') indent++;
        String close = "\n" + " ".repeat(indent) + "}";
        int end = source.indexOf(close, hit);
        assertTrue(end > hit, "SETUP: production member end missing: " + marker);
        return source.substring(start, end + close.length()) + "\n";
    }

    @AfterAll static void closeIsolatedLoader() throws Exception {
        if (isolatedLoader != null) isolatedLoader.close();
    }
}
