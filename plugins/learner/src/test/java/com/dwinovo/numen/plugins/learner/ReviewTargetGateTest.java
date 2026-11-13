package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.Memo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08 C1 review-result commit gate. */
class ReviewTargetGateTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static Memo memo(String ref, String version) {
        return new Memo("m", "p", "EXPERIENCE_EXPAND", "t", "ref=" + ref + " version=" + version, 1L, "learning", List.of());
    }

    private static com.dwinovo.numen.api.WorldContextStamp.Token reviewToken(String world, String server, long epoch) {
        return new com.dwinovo.numen.api.WorldContextStamp.Token(
                java.util.Map.of("world_id", world, "server_session", server), epoch);
    }
    private static Memo skillMemo(String stage) {
        // A durable sample from a previous session is historical evidence, not the active review token.
        return new Memo("historical-" + stage, "review", stage, "historical sample, not proof of validity",
                "{\"world_id\":\"historical-world\",\"server_session\":\"old-server\",\"epoch\":1}", 1L);
    }
    @Test void historicalSkillSampleCanBeReviewedInANewStableReviewContext() {
        var start = reviewToken("live-current", "server-current", 8L);
        var current = reviewToken("live-current", "server-current", 8L);
        assertAll(
                () -> assertTrue(ReviewTargetGate.check(null, C, skillMemo("skill_delivery"), start, current).ok()),
                () -> assertTrue(ReviewTargetGate.check(null, C, skillMemo("skill_task_result"), start, current).ok()));
    }
    @Test void changedReviewEpochCannotCommitDeliveryVerdict() {
        var check = ReviewTargetGate.check(null, C, skillMemo("skill_delivery"),
                reviewToken("w", "s", 1), reviewToken("w", "s", 2));
        assertAll(() -> assertFalse(check.ok()), () -> assertFalse(check.terminal()));
    }
    @Test void changedReviewServerCannotCommitResultVerdict() {
        var check = ReviewTargetGate.check(null, C, skillMemo("skill_task_result"),
                reviewToken("w", "server-a", 1), reviewToken("w", "server-b", 1));
        assertAll(() -> assertFalse(check.ok()), () -> assertFalse(check.terminal()));
    }
    @Test void changedReviewWorldCannotCommitSkillVerdict() {
        var check = ReviewTargetGate.check(null, C, skillMemo("skill_delivery"),
                reviewToken("world-a", "s", 1), reviewToken("world-b", "s", 1));
        assertAll(() -> assertFalse(check.ok()), () -> assertFalse(check.terminal()));
    }
    @Test void unknownReviewContextCannotBecomePermission() {
        var good = reviewToken("w", "s", 1);
        var unknown = new com.dwinovo.numen.api.WorldContextStamp.Token(null, 0);
        assertAll(
                () -> assertFalse(ReviewTargetGate.check(null, C, skillMemo("skill_delivery"), null, good).ok()),
                () -> assertFalse(ReviewTargetGate.check(null, C, skillMemo("skill_delivery"), good, unknown).ok()),
                () -> assertFalse(ReviewTargetGate.check(null, C, skillMemo("skill_delivery"), reviewToken("", "s", 1), good).ok()),
                () -> assertFalse(ReviewTargetGate.check(null, C, skillMemo("skill_task_result"), good, reviewToken("w", "s", 0)).ok()));
    }
    @Test void legacyEntryCannotAuthorizeSkillReviewWithoutContext() {
        assertAll(
                () -> assertFalse(ReviewTargetGate.check(null, C, skillMemo("skill_delivery")).ok()),
                () -> assertFalse(ReviewTargetGate.check(null, C, skillMemo("skill_task_result")).ok()));
    }
    @Test void unrelatedLegacyMemoKeepsItsOriginalMeaning() {
        Memo ordinary = new Memo("ordinary", "old", "learning", "old sample", "not a skill delivery", 1L);
        assertTrue(ReviewTargetGate.check(null, C, ordinary, null, null).ok());
    }

    @Test void retractedFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"e1\",\"publication\":\"TRIAL\",\"retracted\":true}\n");
        assertFalse(ReviewTargetGate.check(dir, C, memo("exp:e1", "v1")).ok());
    }

    @Test void missingFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"other\",\"publication\":\"TRIAL\",\"retracted\":false}\n");
        assertEquals("MISSING", ReviewTargetGate.check(dir, C, memo("exp:e1", "v1")).reason());
    }

    @Test void versionUnverifiableFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"e1\",\"publication\":\"TRIAL\",\"retracted\":false}\n");
        var c = ReviewTargetGate.check(dir, C, memo("exp:e1", "v1"));
        assertFalse(c.ok(), "版本不可核不得当有效 verdict");
        assertEquals("VERSION_UNVERIFIED", c.reason());
    }

    @Test void nonExpansionMemoNotGated() {
        assertTrue(ReviewTargetGate.check(null, C, new Memo("x", "p", "learning", "t", "s", 1L, "learning", List.of())).ok());
    }

    @Test void exactVersionMatches(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"e1\",\"publication\":\"TRIAL\",\"retracted\":false,\"content_fingerprint\":\"v1\"}\n");
        var c = ReviewTargetGate.check(dir, C, memo("exp:e1", "v1"));
        assertTrue(c.ok(), c.reason());
        assertEquals("OK", c.reason());
    }

    @Test void versionMismatchFailsClosed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("experience-" + C + ".jsonl"),
                "{\"id\":\"e1\",\"publication\":\"TRIAL\",\"retracted\":false,\"content_fingerprint\":\"v2\"}\n");
        var c = ReviewTargetGate.check(dir, C, memo("exp:e1", "v1"));
        assertFalse(c.ok());
        assertEquals("VERSION_MISMATCH", c.reason());
    }
}
