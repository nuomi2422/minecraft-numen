package com.dwinovo.numen.monitor;

import com.dwinovo.numen.api.WorldContextStamp;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-12: acceptance is not persistence; only the real host journal owns run/seq.
 * Exercise its queue, worker and files. Reflect the missing new contract so the
 * test-only revision compiles before implementation and fails by assertion.
 * No Minecraft server, shadow game class or replacement journal is constructed.
 */
class MonitoringJournalAcceptedTest {
    @TempDir Path root;
    @AfterEach void clearStamp() { WorldContextStamp.clear(); }

    @Test void explicitTokenSharesRunAndSequenceWithoutFollowingLaterWorld() throws Exception {
        MonitoringJournal journal = new MonitoringJournal(() -> root, null);
        try {
            WorldContextStamp.set("world-A", "server-A");
            var captured = WorldContextStamp.capture();
            synchronized (journal) {
                assertTrue(accepted(journal, "rdd", "taskchain_snapshot", Map.of("id", "task"), captured));
                assertTrue(accepted(journal, "context", "final_request", Map.of("id", "context"), captured));
                WorldContextStamp.set("world-B", "server-B");
            }
            var task = rows(root.resolve("monitor/rdd.jsonl"), 1).get(0);
            var context = rows(root.resolve("monitor/context.jsonl"), 1).get(0);
            assertEquals("world-A", task.get("world_id").getAsString());
            assertEquals("server-A", task.get("server_session").getAsString());
            assertEquals(captured.epoch(), task.get("world_epoch").getAsLong());
            assertTrue(task.get("stamp_conflict").getAsBoolean());
            assertEquals("world-B", task.get("stamp_world_id").getAsString());
            assertEquals(task.get("session"), context.get("session"));
            assertFalse(task.get("session").getAsString().isBlank());
            assertTrue(task.get("event_seq").getAsLong() < context.get("event_seq").getAsLong());
            assertTrue(accepted(journal, "rdd", "unidentified", Map.of(), null));
            var unknown = rows(root.resolve("monitor/rdd.jsonl"), 2).get(1);
            assertFalse(unknown.has("world_id"));
            assertFalse(unknown.has("server_session"));
            assertFalse(unknown.has("world_epoch"));
            assertEquals(task.get("session"), unknown.get("session"));
        } finally {
            journal.close();
            assertTrue(journal.awaitStopped(3000));
        }
    }

    @Test void acceptedCanStillBePendingAndFullQueueRefuses() throws Exception {
        CountDownLatch resolving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        MonitoringJournal journal = new MonitoringJournal(() -> {
            resolving.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("resolver not released");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError(ex);
            }
            return root;
        }, null);
        try {
            assertTrue(accepted(journal, "rdd", "pending", Map.of(), null));
            assertTrue(resolving.await(3, TimeUnit.SECONDS));
            assertFalse(Files.exists(root.resolve("monitor/rdd.jsonl")), "accepted is not persisted");
            for (int i = 0; i < MonitoringJournal.DEFAULT_CAPACITY; i++) {
                assertTrue(accepted(journal, "rdd", "queued", Map.of("i", i), null));
            }
            assertFalse(accepted(journal, "rdd", "refused", Map.of(), null));
            assertEquals(1L, journal.droppedCount());
            release.countDown();
            assertEquals(MonitoringJournal.DEFAULT_CAPACITY + 1,
                    rows(root.resolve("monitor/rdd.jsonl"), MonitoringJournal.DEFAULT_CAPACITY + 1).size());
        } finally {
            release.countDown();
            journal.close();
            assertTrue(journal.awaitStopped(3000));
        }
    }

    @Test void closedQueueRefusesAndLegacyPublishMethodsRemainVoid() throws Exception {
        MonitoringJournal journal = new MonitoringJournal(() -> root, null);
        journal.close();
        assertTrue(journal.awaitStopped(3000));
        assertFalse(accepted(journal, "rdd", "after_close", Map.of(), null));
        assertEquals(void.class, MonitoringJournal.class.getMethod("publish", String.class, String.class, Map.class).getReturnType());
        assertEquals(void.class, MonitoringJournal.class.getMethod("publish", String.class, String.class,
                Map.class, WorldContextStamp.Token.class).getReturnType());
    }

    private static boolean accepted(MonitoringJournal journal, String category, String type,
                                    Map<String, ?> data, WorldContextStamp.Token token) throws Exception {
        Method method;
        try {
            method = MonitoringJournal.class.getMethod("tryPublish", String.class, String.class,
                    Map.class, WorldContextStamp.Token.class);
        } catch (NoSuchMethodException missing) {
            throw new AssertionError("missing host queue-acceptance contract: MonitoringJournal.tryPublish(..., token)", missing);
        }
        assertEquals(boolean.class, method.getReturnType());
        try { return (boolean) method.invoke(journal, category, type, data, token); }
        catch (InvocationTargetException ex) { throw new AssertionError("real journal failed", ex.getCause()); }
    }

    private static List<JsonObject> rows(Path file, int count) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        do {
            if (Files.exists(file)) {
                String text = Files.readString(file);
                int end = text.lastIndexOf('\n');
                if (end >= 0) {
                    var rows = text.substring(0, end).lines().filter(s -> !s.isBlank())
                            .map(s -> JsonParser.parseString(s).getAsJsonObject()).toList();
                    if (rows.size() >= count) return rows;
                }
            }
            Thread.sleep(10);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("real journal did not flush " + count + " rows to " + file);
    }
}
