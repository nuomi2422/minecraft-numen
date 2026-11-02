package com.dwinovo.numen.plugins.learner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2026-11-03（外部学习者）：review_backlog —— needs_review 此前「有写没读者」（#464/#465），
 * 这里钉住三件事：窗口内计数、按检查项汇总、缺文件必须报 MISSING 而不是 0。
 */
class LearnerStatusToolTest {

    @TempDir
    Path dir;

    @Test
    void reviewBacklogCountsUnreviewedVerdictsInWindow() throws Exception {
        Files.createDirectories(dir.resolve("monitor"));
        String now = Instant.now().toString();
        String old = Instant.now().minusSeconds(3L * 24 * 3600).toString();
        String body = reviewed(now, "[\"Q2\",\"Q3\",\"Q4\"]") + "\n"
                + reviewed(now, "[]") + "\n"
                + reviewed(old, "[\"Q2\"]") + "\n";   // 窗口外：不该计入
        Files.writeString(dir.resolve("monitor/learner.jsonl"), body, StandardCharsets.UTF_8);

        Map<String, Object> out = LearnerStatusTool.reviewBacklog(dir, System.currentTimeMillis());

        assertEquals("OK", out.get("status"), String.valueOf(out));
        assertEquals(2, out.get("verdicts"), "窗口内 2 条判定：" + out);
        assertEquals(1, out.get("with_unreviewed"), "只有一条带 needs_review：" + out);
        @SuppressWarnings("unchecked")
        Map<String, Integer> byCheck = (Map<String, Integer>) out.get("by_check");
        assertEquals(1, byCheck.get("Q2"), "Q2 计 1 次：" + out);
        assertEquals(1, byCheck.get("Q4"), "Q4 计 1 次：" + out);
    }

    @Test
    void missingFileIsReportedNotZero() {
        Map<String, Object> out = LearnerStatusTool.reviewBacklog(dir, System.currentTimeMillis());
        assertEquals("MISSING", out.get("status"), "缺文件必须如实报，不许当 0：" + out);
    }

    private static String reviewed(String ts, String needsReview) {
        return "{\"timestamp\":\"" + ts + "\",\"type\":\"reviewed\",\"data\":{\"verdicts\":["
                + "{\"memo_id\":\"m-1\",\"actions\":[\"WRITE_EXPERIENCE\"],"
                + "\"quality_gate\":{\"verdict\":\"REVIEW_REQUIRED\",\"needs_review\":" + needsReview + "}}]}}";
    }

    /**
     * 2026-11-03：复盘期间 learner_status 会被轮询；learner.jsonl 33MB，不能每次都全扫。
     * 判据：文件没变 → 复用缓存；文件变了 → 必须重算。
     */
    @Test
    void backlogIsCachedUntilTheFileChanges() throws Exception {
        Files.createDirectories(dir.resolve("monitor"));
        java.nio.file.Path log = dir.resolve("monitor/learner.jsonl");
        Files.writeString(log, reviewed(Instant.now().toString(), "[\"Q2\"]") + "\n", StandardCharsets.UTF_8);

        java.util.Map<String, Object> first = LearnerStatusTool.reviewBacklog(dir, System.currentTimeMillis());
        java.util.Map<String, Object> second = LearnerStatusTool.reviewBacklog(dir, System.currentTimeMillis());
        org.junit.jupiter.api.Assertions.assertSame(first, second);

        Files.writeString(log, reviewed(Instant.now().toString(), "[\"Q3\"]") + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        Files.setLastModifiedTime(log,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000L));
        java.util.Map<String, Object> third = LearnerStatusTool.reviewBacklog(dir, System.currentTimeMillis());
        assertEquals(2, third.get("verdicts"), "文件变了必须重算：" + third);
    }
}
