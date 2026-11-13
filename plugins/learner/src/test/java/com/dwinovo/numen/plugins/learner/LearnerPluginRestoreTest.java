package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.plugins.learner.core.ReviewBatchStore;
import org.junit.jupiter.api.Test;
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
     * This is a controlled startup-consumer test, not proof of V02's next restart.
     */
    @Test void fullQueueStartupKeepsEachClaimedIdOnceButRetainsClaimWhenTotalsDiffer(@TempDir Path dir) throws Exception {
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
            assertTrue(Files.exists(ReviewBatchStore.file(dir, reviewId)));
            assertTrue(Files.exists(dir.resolve(LearnerPlugin.REVIEW_SENTINEL)));
            assertEquals(batch, ReviewBatchStore.load(dir, reviewId));
        }
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
