package com.dwinovo.numen.plugins.learner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-03（外部学习者 #508/#516）：复盘哨兵 —— 复盘在飞时世界重启，账本里会一条不剩。
 * 判据：开始时落哨兵、终态时删、启动时若还在就报 interrupted（且只删自己的）。
 */
class ReviewSentinelTest {

    @TempDir
    Path dir;

    @Test
    void sentinelSurvivesCrashAndIsReadableAtStartup() {
        UUID companion = UUID.randomUUID();
        LearnerPlugin.writeReviewSentinel(dir, "rev-123", companion);

        String[] read = LearnerPlugin.readReviewSentinel(dir);
        assertNotNull(read, "哨兵应该还在（模拟进程在飞行中被杀掉）");
        assertTrue("rev-123".equals(read[0]), String.join(",", read));
        assertTrue(Long.parseLong(read[1]) >= 0L, "age 必须是非负毫秒：" + read[1]);
    }

    @Test
    void cleanFinishClearsOnlyItsOwnSentinel() {
        LearnerPlugin.writeReviewSentinel(dir, "rev-abc", UUID.randomUUID());
        LearnerPlugin.clearReviewSentinel(dir, "rev-other");
        assertNotNull(LearnerPlugin.readReviewSentinel(dir), "别人的 review_id 不许清掉当前哨兵");
        LearnerPlugin.clearReviewSentinel(dir, "rev-abc");
        assertNull(LearnerPlugin.readReviewSentinel(dir), "自己的终态要能清干净");
    }

    @Test
    void noSentinelMeansNothingToReport() {
        assertNull(LearnerPlugin.readReviewSentinel(dir));
        assertNull(LearnerPlugin.readReviewSentinel(null));
    }
}
