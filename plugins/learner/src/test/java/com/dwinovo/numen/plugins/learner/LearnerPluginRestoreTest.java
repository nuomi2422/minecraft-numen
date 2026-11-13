package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.plugins.learner.core.ReviewBatchStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08 bug-17: end-to-end drained batch -> process death -> restart restore. */
class LearnerPluginRestoreTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private static Memo memo(String id) {
        return new Memo(id, "p", "stage", "tried", "snap", 123L, "learning", List.of());
    }

    /**
     * 2026-11-13 characterization: a full snapshot queue already contains the three
     * claimed IDs. Production restore returns total depth (64), not batch size (3).
     * Legacy behavior only, NOT the desired cleanup contract: when all original IDs
     * are durable, retaining the sentinel may reflect a total-depth/batch-count mismatch.
     * This is a controlled startup-consumer test, not proof of V02's next restart.
     */
    @Disabled("Legacy total-depth/batch-count mismatch frozen in 536e78006; superseded by intended cleanup contract below")
    @Test void legacyFullQueueStartupKeepsEachClaimedIdOnceButRetainsClaimWhenTotalsDiffer(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("memos.json");
        MemoQueue queue = new MemoQueue(file);
        for (int i = 0; i < MemoQueue.MAX_QUEUE; i++) assertTrue(queue.append(memo("original-" + i)));
        assertEquals(64, queue.size());
        List<Memo> batch = queue.drain(3);
        assertEquals(List.of("original-0", "original-1", "original-2"), batch.stream().map(Memo::id).toList());
        assertEquals(64, new MemoQueue(file).size());
        String reviewId = "rev-startup-full64-existing";
        assertTrue(ReviewBatchStore.save(dir, reviewId, batch));
        assertEquals(batch, ReviewBatchStore.load(dir, reviewId));
        LearnerPlugin.writeReviewSentinel(dir, reviewId, C);
        for (int attempt = 0; attempt < 2; attempt++) {
            assertEquals(64, LearnerPlugin.restoreInterruptedReview(dir, companion -> {
                assertEquals(C, companion, "restore must resolve the sentinel's original companion");
                return new MemoQueue(file);
            }));
            List<Memo> reloaded = new MemoQueue(file).all();
            assertEquals(64, reloaded.size());
            for (Memo original : batch) {
                assertEquals(1L, reloaded.stream().filter(m -> original.id().equals(m.id())).count());
                assertEquals(original, reloaded.stream().filter(m -> original.id().equals(m.id())).findFirst().orElseThrow());
            }
            // Characterize current cleanup criterion (total depth == batch size), not
            // actual loss: these durable IDs are present even though the claim remains.
            // Keeping the files is observed legacy behavior, not an assertion that it is correct.
            assertTrue(Files.exists(ReviewBatchStore.file(dir, reviewId)));
            assertTrue(Files.exists(dir.resolve(LearnerPlugin.REVIEW_SENTINEL)));
            assertEquals(batch, ReviewBatchStore.load(dir, reviewId));
        }
    }

    /** Intended contract: durable inclusion of the exact batch, not total queue depth. */
    @Test void fullQueueWithAllOriginalMemosReportsThreeAndClearsItsOwnRetryEvidence(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("memos.json");
        MemoQueue queue = new MemoQueue(file);
        for (int i = 0; i < MemoQueue.MAX_QUEUE; i++) assertTrue(queue.append(memo("original-" + i)));
        List<Memo> batch = queue.drain(3);
        assertEquals(List.of("original-0", "original-1", "original-2"), batch.stream().map(Memo::id).toList());
        assertEquals(64, queue.size(), "normal drain leaves original memos in the queue");
        String reviewId = "rev-startup-full64-intended";
        assertTrue(ReviewBatchStore.save(dir, reviewId, batch));
        LearnerPlugin.writeReviewSentinel(dir, reviewId, C);
        assertEquals(3, LearnerPlugin.restoreInterruptedReview(dir, companion -> {
            assertEquals(C, companion);
            return new MemoQueue(file);
        }), "report restored batch members, not total queue depth 64");
        List<Memo> reloaded = new MemoQueue(file).all();
        assertEquals(64, reloaded.size());
        for (Memo original : batch) {
            assertEquals(1L, reloaded.stream().filter(m -> original.id().equals(m.id())).count());
            assertEquals(original, reloaded.stream().filter(m -> original.id().equals(m.id())).findFirst().orElseThrow());
        }
        assertFalse(Files.exists(ReviewBatchStore.file(dir, reviewId)), "all exact batch objects are durable: clear this claim");
        assertFalse(Files.exists(dir.resolve(LearnerPlugin.REVIEW_SENTINEL)));
        assertEquals(0, LearnerPlugin.restoreInterruptedReview(dir, companion -> {
            fail("completed startup recovery must not resolve or restore the batch twice");
            return new MemoQueue(file);
        }));
    }

    @Test void fullQueueMissingOneOriginalMemoKeepsDurableRetryEvidence(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("memos.json");
        MemoQueue queue = new MemoQueue(file);
        for (int i = 0; i < MemoQueue.MAX_QUEUE; i++) assertTrue(queue.append(memo("original-" + i)));
        List<Memo> batch = queue.drain(3);
        assertEquals(3, batch.size());
        String reviewId = "rev-startup-full64-missing-one";
        assertTrue(ReviewBatchStore.save(dir, reviewId, batch));
        LearnerPlugin.writeReviewSentinel(dir, reviewId, C);
        // Controlled API counterexample only: public removal + refill. Normal snapshot
        // drain does not do this, and this fixture does NOT prove V02 lost any memo.
        assertEquals(1, queue.commitDrain(List.of(batch.get(2))));
        assertTrue(queue.append(memo("replacement")));
        assertEquals(64, queue.size());
        assertEquals(-1, LearnerPlugin.restoreInterruptedReview(dir, companion -> new MemoQueue(file)),
                "a full queue with a missing batch member must not report successful batch recovery");
        List<Memo> reloaded = new MemoQueue(file).all();
        assertEquals(64, reloaded.size());
        for (Memo original : batch.subList(0, 2)) {
            assertEquals(1L, reloaded.stream().filter(m -> original.equals(m)).count());
        }
        assertEquals(0L, reloaded.stream().filter(m -> batch.get(2).id().equals(m.id())).count());
        assertTrue(Files.exists(ReviewBatchStore.file(dir, reviewId)));
        assertTrue(Files.exists(dir.resolve(LearnerPlugin.REVIEW_SENTINEL)));
        assertEquals(batch, ReviewBatchStore.load(dir, reviewId), "retain exact original objects for a later retry");
    }

    @Test void drainedBatchRestoresSameIdsOnce(@TempDir Path dir) throws Exception {
        MemoQueue q = new MemoQueue(dir.resolve("memos.json"));
        q.append(memo("m1"));
        q.append(memo("m2"));
        var batch = q.drain(2);
        assertEquals(2, q.size(), "drain 是快照，未 commit 前队列仍保留（失败可原样恢复）");
        assertTrue(ReviewBatchStore.save(dir, "rev-restore", batch));
        LearnerPlugin.writeReviewSentinel(dir, "rev-restore", C);

        assertEquals(2, LearnerPlugin.restoreInterruptedReview(dir, id -> q));
        assertEquals(2, q.size());
        assertEquals(List.of("m1", "m2"), q.all().stream().map(Memo::id).toList());
        assertEquals(2, q.all().size(), "restore 必须按 id 去重，不产生副本");
        assertFalse(Files.exists(ReviewBatchStore.file(dir, "rev-restore")), "恢复成功才删 batch");
        assertFalse(Files.exists(dir.resolve(LearnerPlugin.REVIEW_SENTINEL)), "恢复成功才删哨兵");
        assertEquals(0, LearnerPlugin.restoreInterruptedReview(dir, id -> q), "重复重启不重复恢复");
    }

    @Test void restoreFailureKeepsSentinelAndBatch(@TempDir Path dir) throws Exception {
        var batch = List.of(memo("m1"));
        assertTrue(ReviewBatchStore.save(dir, "rev-fail", batch));
        LearnerPlugin.writeReviewSentinel(dir, "rev-fail", C);
        Path blocker = dir.resolve("blocked");
        Files.writeString(blocker, "x");
        MemoQueue bad = new MemoQueue(blocker.resolve("memos.json"));
        assertEquals(-1, LearnerPlugin.restoreInterruptedReview(dir, id -> bad));
        assertTrue(Files.exists(ReviewBatchStore.file(dir, "rev-fail")), "失败保留唯一副本");
        assertTrue(Files.exists(dir.resolve(LearnerPlugin.REVIEW_SENTINEL)), "失败保留哨兵");
    }
}
