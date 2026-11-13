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
        // 2026-11-14: getMethod enumerated unrelated MC signatures and failed on ServerPlayer.
        // Keep the exact public static boolean contract without enumerating host methods.
        java.lang.invoke.MethodHandles.publicLookup().findStatic(NumenPlugins.class, "tryPublishMonitor",
                java.lang.invoke.MethodType.methodType(boolean.class,
                        String.class, String.class, Map.class, WorldContextStamp.Token.class));
        Method offer = contract(MonitoringJournal.class, "tryPublish", String.class, String.class, Map.class, WorldContextStamp.Token.class);
        MonitoringJournal journal = MonitoringJournal.get();
        var rootField = MonitoringJournal.class.getDeclaredField("root");
        rootField.setAccessible(true);
        Object previousRoot = rootField.get(journal);
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
                assertTrue(NumenPlugins.tryPublishMonitor("context", "final_request", data, a));
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
            // Drain this fixture and restore configuration, leaving the singleton alive.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (journal.queueSize() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
            synchronized (journal) {
                Method cleanup = MonitoringJournal.class.getDeclaredMethod("closeWriters");
                cleanup.setAccessible(true);
                cleanup.invoke(journal);
                rootField.set(journal, previousRoot);
            }
        }
    }


    /**
     * 2026-11-13 A1: real future completion after a world switch must retain A,
     * while B and missing-token events remain separate. No fake sink or MC server.
     * This exercises serialization, not execution of the Minecraft host callback.
     */
    @Test void planningEventsSerializeMixedScopesAndLateCallbackWithoutInventingRequestId() throws Exception {
        MonitoringJournal journal = MonitoringJournal.get();
        var rootField = MonitoringJournal.class.getDeclaredField("root");
        rootField.setAccessible(true);
        Object previousRoot = rootField.get(journal);
        Method cleanup = MonitoringJournal.class.getDeclaredMethod("closeWriters");
        cleanup.setAccessible(true);
        synchronized (journal) {
            cleanup.invoke(journal);
            journal.configure(root);
        }
        String marker = UUID.randomUUID().toString();
        Map<String, ?> data = Map.of("fixture", marker, "companionId", "same-companion",
                "primary", "same-primary", "subtask", "same-subtask");
        try {
            WorldContextStamp.set("planning-A", "server-A");
            var original = WorldContextStamp.capture();
            var pending = new java.util.concurrent.CompletableFuture<Void>();
            var late = pending.thenRun(() ->
                    assertTrue(RddMonitor.publish("replanned", data, original)));
            WorldContextStamp.set("planning-B", "server-B");
            var current = WorldContextStamp.capture();
            assertTrue(pending.complete(null));
            late.get(8, TimeUnit.SECONDS);
            var old = await(root.resolve("monitor/rdd.jsonl"), "replanned", marker);
            assertEquals("planning-A", old.get("world_id").getAsString());
            assertEquals("server-A", old.get("server_session").getAsString());
            assertEquals(original.epoch(), old.get("world_epoch").getAsLong());
            assertTrue(old.get("stamp_conflict").getAsBoolean());
            assertEquals("planning-B", old.get("stamp_world_id").getAsString());

            long previousSeq = old.get("event_seq").getAsLong();
            for (String type : List.of("survival_preempt", "replanned", "replan_exhausted",
                    "goal_kickoff", "goal_kickoff_skipped")) {
                String scopedMarker = marker + "-" + type;
                Map<String, ?> sameData = Map.of("fixture", scopedMarker,
                        "companionId", "same-companion", "primary", "same-primary");
                // Keep B installed until all writes finish, making conflict assertions deterministic.
                assertTrue(RddMonitor.publish(type, sameData, original));
                assertTrue(RddMonitor.publish(type, sameData, current));
                assertTrue(RddMonitor.publish(type, sameData));
                var rows = awaitCount(root.resolve("monitor/rdd.jsonl"), type, scopedMarker, 3);
                assertEquals(3, rows.size());
                assertEquals("planning-A", rows.get(0).get("world_id").getAsString());
                assertEquals("server-A", rows.get(0).get("server_session").getAsString());
                assertEquals(original.epoch(), rows.get(0).get("world_epoch").getAsLong());
                assertTrue(rows.get(0).get("stamp_conflict").getAsBoolean());
                assertEquals("planning-B", rows.get(1).get("world_id").getAsString());
                assertEquals("server-B", rows.get(1).get("server_session").getAsString());
                assertEquals(current.epoch(), rows.get(1).get("world_epoch").getAsLong());
                assertFalse(rows.get(1).has("stamp_conflict"));
                assertFalse(rows.get(2).has("world_id"));
                assertFalse(rows.get(2).has("server_session"));
                assertFalse(rows.get(2).has("world_epoch"));
                for (var row : rows) {
                    assertEquals(old.get("session"), row.get("session"));
                    assertTrue(row.get("event_seq").getAsLong() > previousSeq);
                    previousSeq = row.get("event_seq").getAsLong();
                    assertFalse(row.has("requestId"));
                    assertFalse(row.getAsJsonObject("data").has("requestId"),
                            "nearby context is not a request correlation");
                }
            }
            // Invoke the real pure guard, which has no actual server and must stay UNKNOWN.
            String description = "日常巡检-" + marker;
            var spec = new com.dwinovo.numen.rdd.api.SubtaskSpec(description,
                    Map.of("asset_key", "minecraft:cobblestone", "minimum", 1), null);
            var filtered = RddPlanGuard.filterSubtasks(List.of(spec), "日常维护", Map.of());
            assertEquals(com.dwinovo.numen.rdd.api.NodeType.DAILY, filtered.allowed().get(0).nodeType());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            JsonObject guard = null;
            do {
                guard = rows(root.resolve("monitor/rdd.jsonl")).stream()
                        .filter(r -> "plan_node_downgraded".equals(r.get("type").getAsString()))
                        .filter(r -> description.equals(r.getAsJsonObject("data").get("description").getAsString()))
                        .findFirst().orElse(null);
                if (guard == null) Thread.sleep(10);
            } while (guard == null && System.nanoTime() < deadline);
            assertNotNull(guard, "real guard observation must flush");
            assertFalse(guard.has("world_id"), "pure guard must not adopt global world B");
            assertFalse(guard.has("server_session"));
            assertFalse(guard.getAsJsonObject("data").has("requestId"));
        } finally {
            WorldContextStamp.clear();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (journal.queueSize() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
            synchronized (journal) {
                cleanup.invoke(journal);
                rootField.set(journal, previousRoot);
            }
        }
    }

    /**
     * 2026-11-13: c89 failed while getMethod enumerated unrelated Minecraft signatures,
     * and the adapter hid its own underlying failure behind false. Call the existing
     * facade directly so JUnit retains its original Throwable; do not mock its sink.
     * Run this method alone in a fresh worker when diagnosing first initialization.
     */
    @Test void directFacadePublicationExposesOriginalFailureWithoutMethodEnumeration() throws Exception {
        MonitoringJournal journal = MonitoringJournal.get();
        var rootField = MonitoringJournal.class.getDeclaredField("root");
        rootField.setAccessible(true);
        Object previousRoot = rootField.get(journal);
        Method cleanup = MonitoringJournal.class.getDeclaredMethod("closeWriters");
        cleanup.setAccessible(true);
        synchronized (journal) {
            cleanup.invoke(journal);
            journal.configure(root);
        }
        // Resource cleanup suppresses its own failure onto an original publication failure.
        try (AutoCloseable restore = () -> {
            WorldContextStamp.clear();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (journal.queueSize() > 0 && System.nanoTime() < deadline) Thread.sleep(10);
            synchronized (journal) {
                cleanup.invoke(journal);
                rootField.set(journal, previousRoot);
            }
        }) {
            String marker = UUID.randomUUID().toString();
            WorldContextStamp.set("diagnostic-world", "diagnostic-server");
            var token = WorldContextStamp.capture();
            // Deliberately bypass RddMonitor's catch, not the real production facade.
            assertTrue(NumenPlugins.tryPublishMonitor("rdd", "direct_facade_diagnostic",
                    Map.of("fixture", marker), token), "the real singleton queue must accept");
            var written = await(root.resolve("monitor/rdd.jsonl"), "direct_facade_diagnostic", marker);
            assertEquals("diagnostic-world", written.get("world_id").getAsString());
            assertEquals("diagnostic-server", written.get("server_session").getAsString());
            assertEquals(token.epoch(), written.get("world_epoch").getAsLong());
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
