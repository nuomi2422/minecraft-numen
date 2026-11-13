package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.dwinovo.numen.plugins.learner.core.ReviewBatchStore;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08 bug-17: graceful abort is terminal+restore exactly once. */
class LearnerPluginAbortTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000ee");

    private static void setConfig(Path dir) throws Exception {
        Field f = LearnerPlugin.class.getDeclaredField("configDir");
        f.setAccessible(true);
        f.set(null, dir);
    }

    private static Memo memo(String id) {
        return new Memo(id, "p", "stage", "tried", "snap", 123L, "learning", List.of());
    }

    private static MemoQueue fullQueue(Path file) {
        MemoQueue queue = new MemoQueue(file);
        for (int i = 0; i < MemoQueue.MAX_QUEUE; i++) assertTrue(queue.append(memo("original-" + i)));
        assertEquals(64, queue.size());
        return queue;
    }

    /** 2026-11-13: controlled fixtures, not V02 memo IDs or evidence of a world restart. */
    @Test void fullQueueSnapshotSurvivesDoubleAbortWithEachClaimedIdOnceOnDisk(@TempDir Path dir) throws Exception {
        setConfig(dir);
        String id = "rev-abort-full64-existing";
        MemoQueue queue = fullQueue(dir.resolve("memos.json"));
        List<Memo> batch = queue.drain(3);
        assertEquals(List.of("original-0", "original-1", "original-2"), batch.stream().map(Memo::id).toList());
        assertEquals(64, new MemoQueue(queue.file()).size(), "drain is a snapshot, not removal");
        LearnerPlugin.beginReview(id, C, "RUNNING", batch.size());
        assertTrue(ReviewBatchStore.save(dir, id, batch));
        assertEquals(batch, ReviewBatchStore.load(dir, id), "claim must retain the exact three memos");
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger returnedSize = new AtomicInteger(-1);
        // Exercise the existing registration/queue/store ports, not a copied restore algorithm.
        // onServerCall and its Minecraft shell are NOT invoked by this characterization.
        LearnerPlugin.registerInflight(id, () -> {
            calls.incrementAndGet();
            returnedSize.set(queue.restore(batch));
            ReviewBatchStore.delete(dir, id);
        });
        assertEquals(1, LearnerPlugin.abortInFlight());
        assertEquals(0, LearnerPlugin.abortInFlight());
        assertEquals(1, calls.get());
        assertEquals(64, returnedSize.get(), "restore returns total queue size, not three restored IDs");
        assertEquals("INTERRUPTED", LearnerPlugin.review(id).state());
        assertTrue(LearnerPlugin.wasAborted(id)); // Does not invoke the late completeOnServer consumer.
        List<Memo> reloaded = new MemoQueue(queue.file()).all();
        assertEquals(64, reloaded.size());
        for (Memo original : batch) {
            assertEquals(1L, reloaded.stream().filter(m -> original.id().equals(m.id())).count());
            assertEquals(original, reloaded.stream().filter(m -> original.id().equals(m.id())).findFirst().orElseThrow());
        }
        assertFalse(Files.exists(ReviewBatchStore.file(dir, id)));
        assertFalse(Files.exists(dir.resolve(LearnerPlugin.REVIEW_SENTINEL)));
    }

    @Test void abortCallbackReturningNormallyDoesNotProveMissingIdsFitAFullQueue(@TempDir Path dir) throws Exception {
        setConfig(dir);
        String id = "rev-abort-full64-artificial-missing";
        MemoQueue queue = fullQueue(dir.resolve("memos.json"));
        List<Memo> batch = queue.drain(3);
        LearnerPlugin.beginReview(id, C, "RUNNING", batch.size());
        assertTrue(ReviewBatchStore.save(dir, id, batch));
        // Explicit adversarial intervention: unlike ordinary snapshot drain, remove the
        // claimed memos and refill their slots. This is NOT a claim that V02 lost samples.
        assertEquals(3, queue.commitDrain(batch));
        for (int i = 0; i < 3; i++) assertTrue(queue.append(memo("replacement-" + i)));
        assertEquals(64, queue.size());
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger returnedSize = new AtomicInteger(-1);
        LearnerPlugin.registerInflight(id, () -> {
            calls.incrementAndGet();
            returnedSize.set(queue.restore(batch));
            ReviewBatchStore.delete(dir, id);
        });
        assertEquals(1, LearnerPlugin.abortInFlight());
        assertEquals(0, LearnerPlugin.abortInFlight());
        assertEquals(1, calls.get());
        assertEquals(64, returnedSize.get());
        assertEquals("INTERRUPTED", LearnerPlugin.review(id).state(), "normal callback return is the abort success criterion");
        List<Memo> reloaded = new MemoQueue(queue.file()).all();
        assertEquals(64, reloaded.size());
        for (Memo original : batch) assertEquals(0L, reloaded.stream().filter(m -> original.id().equals(m.id())).count());
        assertFalse(Files.exists(ReviewBatchStore.file(dir, id)), "current callback deletes the claim even in this controlled counterexample");
    }

    @Test void abortRestoresOnceAndWritesInterrupted(@TempDir Path dir) throws Exception {
        setConfig(dir);
        String id = "rev-abort-1";
        LearnerPlugin.beginReview(id, C, "RUNNING", 2);
        AtomicInteger restored = new AtomicInteger();
        LearnerPlugin.registerInflight(id, restored::incrementAndGet);
        assertEquals(1, LearnerPlugin.abortInFlight());
        assertEquals(1, restored.get(), "restore 只跑一次");
        assertEquals("INTERRUPTED", LearnerPlugin.review(id).state());
        assertEquals(0, LearnerPlugin.abortInFlight(), "第二次钩子不重复");
        assertEquals(1, restored.get());
    }

    @Test void restoreFailureKeepsSentinelForRetry(@TempDir Path dir) throws Exception {
        setConfig(dir);
        String id = "rev-abort-2";
        LearnerPlugin.beginReview(id, C, "RUNNING", 1);
        LearnerPlugin.registerInflight(id, () -> { throw new IllegalStateException("io fail"); });
        assertEquals(1, LearnerPlugin.abortInFlight());
        assertEquals("RUNNING", LearnerPlugin.review(id).state(), "恢复失败不得写终态");
        assertTrue(Files.exists(dir.resolve(LearnerPlugin.REVIEW_SENTINEL)), "哨兵保留供重启重试");
    }

    @Test void normalCompletionClearsInflight(@TempDir Path dir) throws Exception {
        setConfig(dir);
        String id = "rev-abort-3";
        LearnerPlugin.beginReview(id, C, "RUNNING", 1);
        AtomicInteger restored = new AtomicInteger();
        LearnerPlugin.registerInflight(id, restored::incrementAndGet);
        LearnerPlugin.completeInflight(id);
        assertEquals(0, LearnerPlugin.abortInFlight());
        assertEquals(0, restored.get(), "正常完成的 review 不得被关服恢复");
    }
}
