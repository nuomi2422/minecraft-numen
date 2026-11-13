package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.plugins.learner.core.ReviewBatchStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-14: review observation must not widen or disable the real review/restore authority.
 * The red ABI's false ACK may satisfy refusal controls; that is not an implemented scope gate.
 * begin/finish/abort, MemoQueue, BatchStore and singleton journal remain real production consumers.
 */
class LearnerReviewObservationTest {
    @Test void delayedChangedOrUnknownScopesCannotPublishTerminalOrBlockSettlement(@TempDir Path dir) throws Exception {
        try (var f = new LearnerMonitorJournalTest.Fixture(dir)) {
            WorldContextStamp.set("world-A", "server-A");
            var start = WorldContextStamp.capture();
            String id = "late-review-" + UUID.randomUUID();
            var observation = LearnerPlugin.beginReview(id, f.companion, "RUNNING", 1, start);
            List<WorldContextStamp.Token> currents = new ArrayList<>();
            currents.add(new WorldContextStamp.Token(start.identity(), start.epoch() + 1));
            currents.add(new WorldContextStamp.Token(Map.of("world_id", "world-B", "server_session", "server-A"), start.epoch()));
            currents.add(new WorldContextStamp.Token(Map.of("world_id", "world-A", "server_session", "server-B"), start.epoch()));
            currents.add(null);
            currents.add(new WorldContextStamp.Token(null, 0));
            currents.add(new WorldContextStamp.Token(Map.of("world_id", "unknown-server", "server_session", "server-A"), start.epoch()));
            currents.add(new WorldContextStamp.Token(start.identity(), 0));
            WorldContextStamp.set("world-B", "server-B");
            for (var current : currents) {
                var pending = new CompletableFuture<WorldContextStamp.Token>();
                var terminal = pending.thenApply(now -> observation.publish("reviewed",
                        Map.of("review_id", id, "reviewed", 1), now));
                assertTrue(pending.complete(current));
                assertFalse(terminal.get(8, TimeUnit.SECONDS), "changed or UNKNOWN current cannot receive a scoped terminal");
                f.barrier();
                assertTrue(f.rows(id).stream().noneMatch(row -> "reviewed".equals(row.get("type").getAsString())),
                        "no old-scope terminal and no replacement row stamped from current world B");
            }
            LearnerPlugin.finishReview(id, "DONE", 1);
            assertEquals("DONE", LearnerPlugin.review(id).state(),
                    "observation refusal does not authorize or block the old business settlement");
            for (var unknownStart : currents.subList(3, currents.size())) {
                String unknownId = "unknown-start-" + UUID.randomUUID();
                var unknown = LearnerPlugin.beginReview(unknownId, f.companion, "RUNNING", 1, unknownStart);
                assertFalse(unknown.publish("review_stale", Map.of("review_id", unknownId), start));
                LearnerPlugin.finishReview(unknownId, "STALE_WORLD", 0);
                assertEquals("STALE_WORLD", LearnerPlugin.review(unknownId).state());
                f.barrier();
                assertTrue(f.rows(unknownId).stream().noneMatch(row -> "review_stale".equals(row.get("type").getAsString())));
            }
        }
    }

    @Test void realDoubleAbortRestoresBatchOnceAndPublishesCapturedInterruption(@TempDir Path dir) throws Exception {
        try (var f = new LearnerMonitorJournalTest.Fixture(dir)) {
            WorldContextStamp.set("abort-world", "abort-server");
            var start = WorldContextStamp.capture();
            String id = "abort-review-" + UUID.randomUUID();
            MemoQueue queue = new MemoQueue(dir.resolve("memos.json"));
            assertTrue(queue.append(memo("original-one")));
            assertTrue(queue.append(memo("original-two")));
            List<Memo> batch = queue.drain(2); // Existing snapshot drain, not a copied controller.
            var observation = LearnerPlugin.beginReview(id, f.companion, "RUNNING", batch.size(), start);
            assertTrue(ReviewBatchStore.save(dir, id, batch));
            AtomicInteger restored = new AtomicInteger();
            LearnerPlugin.registerInflight(id, () -> {
                restored.incrementAndGet();
                queue.restore(batch);
                ReviewBatchStore.delete(dir, id);
            }, observation);
            assertEquals(1, LearnerPlugin.abortInFlight(start));
            assertEquals(0, LearnerPlugin.abortInFlight(start));
            assertEquals(1, restored.get());
            assertEquals("INTERRUPTED", LearnerPlugin.review(id).state());
            assertTrue(LearnerPlugin.wasAborted(id));
            assertFalse(Files.exists(ReviewBatchStore.file(dir, id)));
            assertFalse(Files.exists(dir.resolve(LearnerPlugin.REVIEW_SENTINEL)));
            List<Memo> durable = new MemoQueue(queue.file()).all();
            assertEquals(2, durable.size());
            for (Memo original : batch) {
                assertEquals(1L, durable.stream().filter(m -> original.id().equals(m.id())).count());
                assertTrue(durable.contains(original));
            }
            WorldContextStamp.set("later-world", "later-server");
            f.barrier();
            var rows = f.requireRows(id, "review_started", "review_interrupted");
            var terminal = rows.get(1);
            assertEquals(start.identity().get("world_id"), terminal.get("world_id").getAsString());
            assertEquals(start.identity().get("server_session"), terminal.get("server_session").getAsString());
            assertEquals(start.epoch(), terminal.get("world_epoch").getAsLong());
            assertEquals(rows.get(0).get("session"), terminal.get("session"));
            assertTrue(rows.get(0).get("event_seq").getAsLong() < terminal.get("event_seq").getAsLong());
            assertEquals("OK", terminal.getAsJsonObject("data").get("restore").getAsString());
        }
    }

    @Test void restoreExceptionKeepsClaimAndSentinelAndOnlyReportsFailure(@TempDir Path dir) throws Exception {
        try (var f = new LearnerMonitorJournalTest.Fixture(dir)) {
            WorldContextStamp.set("restore-world", "restore-server");
            var start = WorldContextStamp.capture();
            String id = "restore-failed-" + UUID.randomUUID();
            MemoQueue queue = new MemoQueue(dir.resolve("memos.json"));
            assertTrue(queue.append(memo("durable-original")));
            List<Memo> batch = queue.drain(1);
            var observation = LearnerPlugin.beginReview(id, f.companion, "RUNNING", 1, start);
            assertTrue(ReviewBatchStore.save(dir, id, batch));
            AtomicInteger attempts = new AtomicInteger();
            LearnerPlugin.registerInflight(id, () -> {
                attempts.incrementAndGet();
                throw new IllegalStateException("fixture restore IO failure");
            }, observation);
            assertEquals(1, LearnerPlugin.abortInFlight(start));
            assertEquals(0, LearnerPlugin.abortInFlight(start));
            assertEquals(1, attempts.get());
            assertEquals("RUNNING", LearnerPlugin.review(id).state(), "failed restoration is not a successful terminal");
            assertTrue(Files.exists(dir.resolve(LearnerPlugin.REVIEW_SENTINEL)));
            assertEquals(batch, ReviewBatchStore.load(dir, id));
            assertEquals(batch, new MemoQueue(queue.file()).all());
            f.barrier();
            var rows = f.requireRows(id, "review_started", "review_interrupted");
            var diagnostic = rows.get(1);
            assertEquals("FAILED", diagnostic.getAsJsonObject("data").get("restore").getAsString());
            assertEquals(start.identity().get("world_id"), diagnostic.get("world_id").getAsString());
            assertEquals(start.identity().get("server_session"), diagnostic.get("server_session").getAsString());
            assertEquals(start.epoch(), diagnostic.get("world_epoch").getAsLong());
            assertEquals("RUNNING", LearnerPlugin.review(id).state(), "observation must leave recovery retryable");
        }
    }

    private static Memo memo(String id) {
        return new Memo(id, "fixture", "stage", "tried", "snapshot", 123L, "learning", List.of());
    }
}
