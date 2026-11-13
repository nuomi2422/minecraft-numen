package com.dwinovo.numen.plugins.learner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-13 red batch: auto review after restart must be driven by the persisted
 * ReviewState file, not by in-memory maps. Controls drive the real consumers
 * (recordReview / remember* / readReviewTimes) and the real autoReviewDue decision.
 * No Java, no world, no game.
 */
class ReviewStatePersistenceRedTest {
    private static final UUID C = UUID.fromString("a43d753e-790d-472b-8d5b-dc18ac0afbc0");

    @TempDir Path dir;

    @AfterEach void clearConfigDir() throws Exception {
        Field f = LearnerPlugin.class.getDeclaredField("configDir");
        f.setAccessible(true);
        f.set(null, null);
    }

    private void install(Path d) throws Exception {
        Field f = LearnerPlugin.class.getDeclaredField("configDir");
        f.setAccessible(true);
        f.set(null, d);
    }

    private Path stateFile() { return dir.resolve("learner-review-state.json"); }

    private String extended(long nudge, long auto, long started) {
        return "{\"last_review_at\":\"2026-11-12T00:00:00Z\",\"last_verdict_count\":1,"
                + "\"future_field\":{\"keep\":true},\"companions\":{\"" + C + "\":{\"last_nudge_at\":" + nudge
                + ",\"last_auto_review_at\":" + auto + ",\"last_review_started_at\":" + started + "}}}";
    }

    @Test void recordReviewPreservesExtendedCompanionStateAndUnknownFields() throws Exception {
        install(dir);
        Files.writeString(stateFile(), extended(111L, 222L, 333L), StandardCharsets.UTF_8);
        LearnerPlugin.recordReview("2026-11-13T00:00:00Z", 3);
        JsonObject out = JsonParser.parseString(Files.readString(stateFile(), StandardCharsets.UTF_8)).getAsJsonObject();
        JsonObject c = out.getAsJsonObject("companions").getAsJsonObject(C.toString());
        assertAll(
                () -> assertEquals("2026-11-13T00:00:00Z", out.get("last_review_at").getAsString()),
                () -> assertEquals(3, out.get("last_verdict_count").getAsInt()),
                () -> assertTrue(out.has("future_field"), "unknown state fields must survive a review completion"),
                () -> assertEquals(111L, c.get("last_nudge_at").getAsLong()),
                () -> assertEquals(333L, c.get("last_review_started_at").getAsLong()));
    }

    @Test void restartReadsPersistedNudgeAndAutoReviewIsDue() throws Exception {
        install(dir);
        long now = System.currentTimeMillis();
        long nudge = now - 11 * 60_000L;
        Files.writeString(stateFile(), extended(nudge, 0L, 0L), StandardCharsets.UTF_8);
        LearnerPlugin.ReviewStateRead read = LearnerPlugin.readReviewTimes(dir, C);
        assertAll(
                () -> assertTrue(read.known(), "a valid state file is known"),
                () -> assertEquals(nudge, read.times().lastNudgeAt()),
                () -> assertEquals(0L, read.times().lastAutoReviewAt()),
                () -> assertTrue(LearnerAutoIntake.autoReviewDue(read.times().lastNudgeAt(),
                                read.times().lastReviewStartedAt(), null, now, 64, false),
                        "an 11-minute-old persisted nudge must still count as unanswered after restart"));
    }

    @Test void nudgeAndAutoWritesSurviveTheRealReviewCompletionWriter() throws Exception {
        install(dir);
        long now = System.currentTimeMillis();
        long nudge = now - 11 * 60_000L;
        LearnerPlugin.rememberNudge(dir, C, nudge);
        LearnerPlugin.recordReview("2026-11-13T00:00:00Z", 5);
        LearnerPlugin.ReviewStateRead afterReview = LearnerPlugin.readReviewTimes(dir, C);
        assertTrue(afterReview.known());
        assertEquals(nudge, afterReview.times().lastNudgeAt(), "review completion must not drop the nudge clock");
        LearnerPlugin.rememberAutoReview(dir, C, now);
        LearnerPlugin.ReviewStateRead afterAuto = LearnerPlugin.readReviewTimes(dir, C);
        assertEquals(now, afterAuto.times().lastAutoReviewAt());
        assertFalse(LearnerAutoIntake.autoReviewDue(nudge, 0L, afterAuto.times().lastAutoReviewAt(), now + 1_000L, 64, false),
                "the 30-minute cooldown must hold after the single automatic start");
    }

    @Test void corruptStateIsUnknownAndNeverRewrittenByTheReader() throws Exception {
        install(dir);
        String broken = "{ broken";
        Files.writeString(stateFile(), broken, StandardCharsets.UTF_8);
        LearnerPlugin.ReviewStateRead corrupt = LearnerPlugin.readReviewTimes(dir, C);
        assertFalse(corrupt.known(), "corrupt state is UNKNOWN, not 'never nudged'");
        assertEquals(broken, Files.readString(stateFile(), StandardCharsets.UTF_8), "reader must not rewrite the file");
        Files.delete(stateFile());
        LearnerPlugin.ReviewStateRead missing = LearnerPlugin.readReviewTimes(dir, C);
        assertAll(
                () -> assertTrue(missing.known(), "missing file is a known empty migration"),
                () -> assertEquals(0L, missing.times().lastNudgeAt(), "missing keeps the old null semantics"));
    }
}
