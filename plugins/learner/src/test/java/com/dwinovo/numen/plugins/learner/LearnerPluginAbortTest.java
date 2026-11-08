package com.dwinovo.numen.plugins.learner;

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
