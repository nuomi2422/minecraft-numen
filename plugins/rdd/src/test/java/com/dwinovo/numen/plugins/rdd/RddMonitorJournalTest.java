package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.monitor.MonitoringJournal;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-12: exercise RddMonitor -> public facade -> the real singleton journal.
 * Only an existing private writer cleanup method is reflected for fixture isolation;
 * the sink, queue, worker, run ID and event sequence are never replaced or mocked.
 * Missing new methods fail explicit contract assertions on the test-only revision.
 */
class RddMonitorJournalTest {
    @TempDir Path root;

    @Test void realFacadeSharesRunPreservesCapturedIdentityAndRetriesQueueRejection() throws Exception {
        Method publish = contract(RddMonitor.class, "publish", String.class, Map.class, WorldContextStamp.Token.class);
        Method changed = contract(RddMonitor.class, "publishIfChanged", String.class, Map.class, WorldContextStamp.Token.class);
        Method facade = contract(NumenPlugins.class, "tryPublishMonitor", String.class, String.class, Map.class, WorldContextStamp.Token.class);
        Method offer = contract(MonitoringJournal.class, "tryPublish", String.class, String.class, Map.class, WorldContextStamp.Token.class);
        MonitoringJournal journal = MonitoringJournal.get();
        synchronized (journal) {
            Method cleanup = MonitoringJournal.class.getDeclaredMethod("closeWriters");
            cleanup.setAccessible(true);
            cleanup.invoke(journal);
            journal.configure(root);
        }
        String marker = UUID.randomUUID().toString();
        Map<String, ?> data = Map.of("fixture", marker, "companionId", "same-companion", "taskId", "same-task");
        try {
            WorldContextStamp.set("world-A", "server-A");
            var a = WorldContextStamp.capture();
            synchronized (journal) {
                assertTrue(invoke(publish, null, "taskchain_snapshot", data, a));
                assertTrue(invoke(facade, null, "context", "final_request", data, a));
                WorldContextStamp.set("world-B", "server-B");
            }
            var task = await(root.resolve("monitor/rdd.jsonl"), "taskchain_snapshot", marker);
            var context = await(root.resolve("monitor/context.jsonl"), "final_request", marker);
            assertEquals("world-A", task.get("world_id").getAsString());
            assertEquals("server-A", task.get("server_session").getAsString());
            assertEquals(a.epoch(), task.get("world_epoch").getAsLong());
            assertTrue(task.get("stamp_conflict").getAsBoolean());
            assertEquals("world-B", task.get("stamp_world_id").getAsString());
            assertEquals(context.get("session"), task.get("session"));
            assertTrue(task.get("event_seq").getAsLong() < context.get("event_seq").getAsLong());
            assertEquals(data.get("taskId"), task.getAsJsonObject("data").get("taskId").getAsString());

            assertTrue(RddMonitor.publish("legacy_unknown", data));
            var unknown = await(root.resolve("monitor/rdd.jsonl"), "legacy_unknown", marker);
            assertFalse(unknown.has("world_id"), "legacy publication must not capture the later global stamp");
            assertFalse(unknown.has("server_session"));
            assertEquals(task.get("session"), unknown.get("session"));

            String dedup = "scope-" + marker;
            var b = WorldContextStamp.capture();
            assertTrue(invoke(changed, null, dedup, data, a));
            assertFalse(invoke(changed, null, dedup, data, a));
            assertTrue(invoke(changed, null, dedup, data, b), "same content in another world is not a duplicate");
            assertTrue(invoke(changed, null, dedup, data, null), "UNKNOWN has its own dedup domain");
            assertFalse(invoke(changed, null, dedup, data, null));
            var scoped = awaitCount(root.resolve("monitor/rdd.jsonl"), dedup, marker, 3);
            assertEquals(3, scoped.size());
            assertEquals("world-A", scoped.get(0).get("world_id").getAsString());
            assertEquals("world-B", scoped.get(1).get("world_id").getAsString());
            assertFalse(scoped.get(2).has("world_id"));

            String retryType = "retry-" + marker;
            int queued = 0;
            synchronized (journal) {
                while (invoke(offer, journal, "fixture", "fill", Map.of("fixture", marker, "i", queued), null)) {
                    if (++queued > MonitoringJournal.DEFAULT_CAPACITY + 1) fail("real bounded queue never refused");
                }
                assertFalse(invoke(changed, null, retryType, data, a));
            }
            assertTrue(queued > 0);
            awaitFill(root.resolve("monitor/fixture.jsonl"), marker, queued - 1);
            assertTrue(invoke(changed, null, retryType, data, a), "refused attempt must not commit the fingerprint");
            assertFalse(invoke(changed, null, retryType, data, a), "accepted retry now deduplicates");
            var retry = await(root.resolve("monitor/rdd.jsonl"), retryType, marker);
            assertEquals(task.get("session"), retry.get("session"));
            assertTrue(retry.get("event_seq").getAsLong() > task.get("event_seq").getAsLong());
        } finally {
            WorldContextStamp.clear();
            // Leave the singleton alive for other real journal tests in this JVM.
            synchronized (journal) {
                Method cleanup = MonitoringJournal.class.getDeclaredMethod("closeWriters");
                cleanup.setAccessible(true);
                cleanup.invoke(journal);
            }
        }
    }

    private static Method contract(Class<?> owner, String name, Class<?>... args) {
        try {
            Method method = owner.getMethod(name, args);
            assertEquals(boolean.class, method.getReturnType(), "must return accepted, not a persistence claim");
            return method;
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("missing producer-to-host contract: " + owner.getSimpleName() + "." + name, missing);
        }
    }

    private static boolean invoke(Method method, Object target, Object... args) throws Exception {
        try { return (boolean) method.invoke(target, args); }
        catch (InvocationTargetException ex) { throw new AssertionError("real publication threw", ex.getCause()); }
    }

    private static JsonObject await(Path file, String type, String marker) throws Exception {
        return awaitCount(file, type, marker, 1).get(0);
    }

    private static List<JsonObject> awaitCount(Path file, String type, String marker, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            var rows = rows(file).stream().filter(r -> type.equals(r.get("type").getAsString()))
                    .filter(r -> marker.equals(r.getAsJsonObject("data").get("fixture").getAsString())).toList();
            if (rows.size() >= count) return rows;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("real singleton journal did not flush " + type + " to " + file);
    }

    private static void awaitFill(Path file, String marker, int last) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            if (rows(file).stream().anyMatch(r -> marker.equals(r.getAsJsonObject("data").get("fixture").getAsString())
                    && r.getAsJsonObject("data").get("i").getAsInt() == last)) return;
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("fixture queue did not drain");
    }

    private static List<JsonObject> rows(Path file) throws Exception {
        if (!Files.exists(file)) return List.of();
        String text = Files.readString(file);
        int end = text.lastIndexOf('\n');
        if (end < 0) return List.of();
        return text.substring(0, end).lines().filter(s -> !s.isBlank())
                .map(s -> JsonParser.parseString(s).getAsJsonObject()).toList();
    }
}
