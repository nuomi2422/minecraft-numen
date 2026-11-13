package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.monitor.MonitoringJournal;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-14: real learner lifecycle/legacy publication must use the existing singleton journal.
 * Only paths and pre-existing process globals are isolated; queue, writer, facade and sink are real.
 * Missing learner rows are the red product assertion, after a successful real-journal barrier.
 */
class LearnerMonitorJournalTest {
    @Test void beginAndTerminalEventsShareCapturedScopeAndJournalSession(@TempDir Path dir) throws Exception {
        try (Fixture f = new Fixture(dir)) {
            WorldContextStamp.set("review-world", "review-server");
            var captured = WorldContextStamp.capture();
            Map<String, String> mutable = new LinkedHashMap<>(captured.identity());
            var producer = new WorldContextStamp.Token(mutable, captured.epoch());
            String done = "review-done-" + UUID.randomUUID();
            String failed = "review-failed-" + UUID.randomUUID();
            boolean doneAccepted;
            boolean quotaAccepted;
            boolean failedAccepted;
            synchronized (f.journal) {
                var observation = LearnerPlugin.beginReview(done, f.companion, "RUNNING", 1, producer);
                mutable.put("world_id", "mutated-after-capture");
                assertEquals(captured, observation.start(), "handle freezes the original producer identity");
                LearnerPlugin.finishReview(done, "DONE", 1);
                doneAccepted = observation.publish("reviewed", Map.of(
                        "review_id", done, "companion", f.companion.toString(),
                        "reviewed", 1, "committed", 1, "restored", 0), captured);
                var failure = LearnerPlugin.beginReview(failed, f.companion, "RUNNING", 1, captured);
                quotaAccepted = failure.publish("review_quota_blocked",
                        Map.of("review_id", failed, "reason", "fixture quota exhausted"), captured);
                LearnerPlugin.finishReview(failed, "FAILED_PROVIDER", 0);
                failedAccepted = failure.publish("review_failed", Map.of(
                        "review_id", failed, "companion", f.companion.toString(),
                        "reason", "fixture quota exhausted", "restored", 1, "queue_depth", 1), captured);
                WorldContextStamp.set("later-world", "later-server");
            }
            assertEquals("DONE", LearnerPlugin.review(done).state(), "observation never controls business settlement");
            assertEquals("FAILED_PROVIDER", LearnerPlugin.review(failed).state());
            f.barrier();
            // Read the actual learner partition before asserting ABI ACKs: no fake-sink or stub-only red.
            var doneRows = f.requireRows(done, "review_started", "reviewed");
            var failureRows = f.requireRows(failed, "review_started", "review_quota_blocked", "review_failed");
            String session = doneRows.get(0).get("session").getAsString();
            long previous = 0;
            for (var row : List.of(doneRows.get(0), doneRows.get(1),
                    failureRows.get(0), failureRows.get(1), failureRows.get(2))) {
                assertEquals("review-world", row.get("world_id").getAsString());
                assertEquals("review-server", row.get("server_session").getAsString());
                assertEquals(captured.epoch(), row.get("world_epoch").getAsLong());
                assertEquals(session, row.get("session").getAsString());
                assertTrue(row.get("event_seq").getAsLong() > previous);
                previous = row.get("event_seq").getAsLong();
            }
            assertEquals(1, doneRows.get(1).getAsJsonObject("data").get("committed").getAsInt());
            assertEquals("fixture quota exhausted",
                    failureRows.get(2).getAsJsonObject("data").get("reason").getAsString());
            assertTrue(doneAccepted && quotaAccepted && failedAccepted, "ACK means real queue acceptance only");
        }
    }

    @Test void legacyPublicationUsesJournalWithoutBorrowingCurrentScope(@TempDir Path dir) throws Exception {
        try (Fixture f = new Fixture(dir)) {
            WorldContextStamp.set("unrelated-current-world", "unrelated-current-server");
            String id = "legacy-review-" + UUID.randomUUID();
            LearnerMonitor.publish("review_stale", Map.of("review_id", id, "restored", 2));
            f.barrier();
            var row = f.requireRows(id, "review_stale").get(0);
            assertFalse(row.has("world_id"));
            assertFalse(row.has("server_session"));
            assertFalse(row.has("world_epoch"));
            assertTrue(row.has("session"));
            assertTrue(row.has("event_seq"));
            assertEquals(2, row.getAsJsonObject("data").get("restored").getAsInt());
        }
    }

    /** Bounded fixture shared by the two test files; never replaces the journal sink or worker. */
    static final class Fixture implements AutoCloseable {
        final Path config;
        final UUID companion = UUID.randomUUID();
        final MonitoringJournal journal = MonitoringJournal.get();
        private final Field rootField = field(MonitoringJournal.class, "root");
        private final Method closeWriters = MonitoringJournal.class.getDeclaredMethod("closeWriters");
        private final Object previousRoot;
        private final Object previousConfig = field(LearnerPlugin.class, "configDir").get(null);
        private final Map<String, Map<Object, Object>> savedMaps = new HashMap<>();
        private final Set<Object> savedAborted;
        private final AtomicReference<Map<String, String>> stamp;
        private final Map<String, String> previousStamp;
        private final AtomicLong epoch;
        private final long previousEpoch;
        private final Object previousTestToken;

        @SuppressWarnings("unchecked")
        Fixture(Path config) throws Exception {
            this.config = config;
            stamp = (AtomicReference<Map<String, String>>) field(WorldContextStamp.class, "STAMP").get(null);
            epoch = (AtomicLong) field(WorldContextStamp.class, "EPOCH").get(null);
            previousStamp = stamp.get();
            previousEpoch = epoch.get();
            previousTestToken = field(WorldContextStamp.class, "TEST_TOKEN").get(null);
            for (String name : List.of("REVIEWS", "LAST_REVIEW_STARTED", "INFLIGHT")) {
                Map<Object, Object> map = (Map<Object, Object>) field(LearnerPlugin.class, name).get(null);
                savedMaps.put(name, new HashMap<>(map));
                map.clear();
            }
            Set<Object> aborted = (Set<Object>) field(LearnerPlugin.class, "ABORTED").get(null);
            savedAborted = new HashSet<>(aborted);
            aborted.clear();
            closeWriters.setAccessible(true);
            synchronized (journal) {
                previousRoot = rootField.get(journal);
                closeWriters.invoke(journal);
                journal.configure(config);
            }
            field(LearnerPlugin.class, "configDir").set(null, config);
        }

        static Field field(Class<?> owner, String name) throws Exception {
            Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        }

        List<JsonObject> rows(String reviewId) throws Exception {
            Path file = config.resolve("monitor/learner.jsonl");
            if (!Files.isRegularFile(file)) return List.of();
            List<JsonObject> rows = new ArrayList<>();
            for (String line : Files.readAllLines(file)) {
                if (line.isBlank()) continue;
                JsonObject row = JsonParser.parseString(line).getAsJsonObject();
                JsonObject data = row.getAsJsonObject("data");
                if (data != null && data.has("review_id") && reviewId.equals(data.get("review_id").getAsString()))
                    rows.add(row);
            }
            return rows;
        }

        List<JsonObject> requireRows(String reviewId, String... types) throws Exception {
            List<JsonObject> rows = rows(reviewId);
            assertEquals(List.of(types), rows.stream().map(r -> r.get("type").getAsString()).toList(),
                    "product red: real shared journal has no matching learner lifecycle rows/envelope");
            return rows;
        }

        void barrier() throws Exception {
            String marker = UUID.randomUUID().toString();
            assertTrue(NumenPlugins.tryPublishMonitor("fixture", "learner_barrier",
                    Map.of("fixture", marker), null), "fixture requires a functioning real host queue");
            Path file = config.resolve("monitor/fixture.jsonl");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            do {
                if (Files.isRegularFile(file) && Files.readString(file).contains(marker)) return;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            fail("fixture barrier did not flush: infrastructure failure, not a learner product red");
        }

        @Override
        @SuppressWarnings("unchecked")
        public void close() throws Exception {
            try {
                barrier(); // FIFO barrier drains all fixture events before root/global restoration.
            } finally {
                try {
                    synchronized (journal) {
                        closeWriters.invoke(journal);
                        rootField.set(journal, previousRoot);
                    }
                } finally {
                    for (var entry : savedMaps.entrySet()) {
                        Map<Object, Object> map =
                                (Map<Object, Object>) field(LearnerPlugin.class, entry.getKey()).get(null);
                        map.clear();
                        map.putAll(entry.getValue());
                    }
                    Set<Object> aborted = (Set<Object>) field(LearnerPlugin.class, "ABORTED").get(null);
                    aborted.clear();
                    aborted.addAll(savedAborted);
                    field(LearnerPlugin.class, "configDir").set(null, previousConfig);
                    stamp.set(previousStamp);
                    epoch.set(previousEpoch);
                    field(WorldContextStamp.class, "TEST_TOKEN").set(null, previousTestToken);
                }
            }
        }
    }
}
