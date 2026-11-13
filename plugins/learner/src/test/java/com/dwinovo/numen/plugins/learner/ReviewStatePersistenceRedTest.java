package com.dwinovo.numen.plugins.learner;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-13 red batch: auto review after restart must be driven by the persisted
 * ReviewState file, not by in-memory maps. Controls drive the real consumers
 * (recordReview / remember* / readReviewTimes) and the real autoReviewDue decision.
 * pure JVM/offline: no world or game.
 */
class ReviewStatePersistenceRedTest {
    private static final UUID C = UUID.fromString("a43d753e-790d-472b-8d5b-dc18ac0afbc0");

    @TempDir Path dir;

    private Object oldConfigDir, oldLastReviewAt, oldLastVerdictCount;
    private Object oldEnabled, oldToggleCheck, oldConfigDirCache, oldNudgeSender, oldQueueResolver, oldLedgerReader;
    private Map<String, Long> oldCursors;
    private Set<String> oldEnqueued;
    private Map<UUID, Integer> oldFailStreak;
    private Map<UUID, Long> oldUndeliverable, oldLastAuto, oldLastNudge;
    private final AtomicInteger sent = new AtomicInteger();

    private static Field field(String name) throws Exception {
        Field f = LearnerPlugin.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Field autoField(String name) throws Exception {
        Field f = LearnerAutoIntake.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    @SuppressWarnings("unchecked")
    @BeforeEach void captureGlobals() throws Exception {
        oldConfigDir = field("configDir").get(null);
        oldLastReviewAt = field("lastReviewAt").get(null);
        oldLastVerdictCount = field("lastVerdictCount").get(null);
        oldEnabled = autoField("enabled").get(null);
        oldToggleCheck = autoField("lastToggleCheck").get(null);
        oldConfigDirCache = autoField("configDirCache").get(null);
        oldNudgeSender = autoField("nudgeSenderForTest").get(null);
        oldQueueResolver = autoField("queueResolver").get(null);
        oldLedgerReader = autoField("deliveryLedgerReader").get(null);
        oldCursors = new LinkedHashMap<>((Map<String, Long>) autoField("CURSORS").get(null));
        oldEnqueued = new LinkedHashSet<>((Set<String>) autoField("ENQUEUED").get(null));
        oldFailStreak = new LinkedHashMap<>((Map<UUID, Integer>) autoField("REVIEW_FAIL_STREAK").get(null));
        oldUndeliverable = new LinkedHashMap<>((Map<UUID, Long>) autoField("NUDGE_UNDELIVERABLE_UNTIL").get(null));
        oldLastAuto = new LinkedHashMap<>((Map<UUID, Long>) autoField("LAST_AUTO_REVIEW_AT").get(null));
        oldLastNudge = new LinkedHashMap<>((Map<UUID, Long>) autoField("LAST_REVIEW_NUDGE").get(null));
        sent.set(0);
    }

    @AfterEach void restoreGlobals() throws Exception {
        field("configDir").set(null, oldConfigDir);
        field("lastReviewAt").set(null, oldLastReviewAt);
        field("lastVerdictCount").set(null, oldLastVerdictCount);
        autoField("enabled").set(null, oldEnabled);
        autoField("lastToggleCheck").set(null, oldToggleCheck);
        autoField("configDirCache").set(null, oldConfigDirCache);
        autoField("nudgeSenderForTest").set(null, oldNudgeSender);
        autoField("queueResolver").set(null, oldQueueResolver);
        autoField("deliveryLedgerReader").set(null, oldLedgerReader);
        restoreMap("CURSORS", oldCursors);
        restoreSet("ENQUEUED", oldEnqueued);
        restoreMap("REVIEW_FAIL_STREAK", oldFailStreak);
        restoreMap("NUDGE_UNDELIVERABLE_UNTIL", oldUndeliverable);
        restoreMap("LAST_AUTO_REVIEW_AT", oldLastAuto);
        restoreMap("LAST_REVIEW_NUDGE", oldLastNudge);
    }

    @SuppressWarnings("unchecked")
    private static void restoreMap(String name, Map<?, ?> saved) throws Exception {
        Map<Object, Object> live = (Map<Object, Object>) autoField(name).get(null);
        live.clear();
        live.putAll(saved);
    }

    @SuppressWarnings("unchecked")
    private static void restoreSet(String name, Set<?> saved) throws Exception {
        Set<Object> live = (Set<Object>) autoField(name).get(null);
        live.clear();
        live.addAll(saved);
    }

    private void install(Path d) throws Exception {
        field("configDir").set(null, d);
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

    private void wireQueueAndSender() throws Exception {
        LearnerAutoIntake.install(dir);
        Path memoFile = dir.resolve("learner-memos-" + C + ".json");
        MemoQueue queue = new MemoQueue(memoFile);
        for (int i = 0; i < 3; i++) {
            assertTrue(queue.append(new Memo("wire-" + i, "p", "death", "t", "{}", System.currentTimeMillis())));
        }
        LearnerAutoIntake.installQueueResolverForTest(id -> queue);
        LearnerAutoIntake.installNudgeSenderForTest((id, text) -> { sent.incrementAndGet(); return true; });
    }

    @Test void nudgeDoesNotResendOrRefreshWhenRestartIsInsideTheWindow() throws Exception {
        wireQueueAndSender();
        long old = System.currentTimeMillis() - 5 * 60_000L;
        assertTrue(LearnerPlugin.rememberNudge(dir, C, old));
        LearnerAutoIntake.maybeNudgeReview();
        LearnerPlugin.ReviewStateRead read = LearnerPlugin.readReviewTimes(dir, C);
        assertAll(
                () -> assertEquals(0, sent.get(), "a 5-minute restart must not re-send"),
                () -> assertEquals(old, read.times().lastNudgeAt(), "a skipped nudge must not refresh the clock"));
    }

    @Test void nudgeSendsOnceAfterTheWindowAndDoesNotRepeat() throws Exception {
        wireQueueAndSender();
        long old = System.currentTimeMillis() - 11 * 60_000L;
        assertTrue(LearnerPlugin.rememberNudge(dir, C, old));
        long before = System.currentTimeMillis();
        LearnerAutoIntake.maybeNudgeReview();
        LearnerAutoIntake.maybeNudgeReview();
        LearnerPlugin.ReviewStateRead read = LearnerPlugin.readReviewTimes(dir, C);
        assertAll(
                () -> assertEquals(1, sent.get(), "an 11-minute reminder is sent exactly once"),
                () -> assertTrue(read.times().lastNudgeAt() >= before, "the sent nudge persists its new time"));
    }

    @Test void malformedStateIsUnknownAndWritesKeepTheOriginalBytes() throws Exception {
        Path f = stateFile();
        String[] bads = {
                "{\"companions\":{\"" + C + "\":\"bad\"}}",
                "{\"companions\":{\"" + C + "\":null}}",
                "{\"companions\":{\"" + C + "\":{\"last_nudge_at\":null}}}",
                "{\"companions\":{\"" + C + "\":{\"last_nudge_at\":-1}}}",
                "{\"companions\":{\"" + C + "\":{\"last_nudge_at\":1.5}}}",
                "{\"companions\":{\"" + C + "\":{\"last_nudge_at\":1e30}}}",
                "{\"companions\":{\"" + C + "\":{\"last_nudge_at\":\"x\"}}}"
        };
        for (String bad : bads) {
            Files.writeString(f, bad, StandardCharsets.UTF_8);
            assertAll(
                    () -> assertFalse(LearnerPlugin.readReviewTimes(dir, C).known(), bad),
                    () -> assertFalse(LearnerPlugin.rememberNudge(dir, C, 123L), bad),
                    () -> assertEquals(bad, Files.readString(f, StandardCharsets.UTF_8), bad));
        }
        Files.delete(f);
        Files.createDirectory(f);
        assertFalse(LearnerPlugin.readReviewTimes(dir, C).known(), "directory state is UNKNOWN");
    }
}
