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
