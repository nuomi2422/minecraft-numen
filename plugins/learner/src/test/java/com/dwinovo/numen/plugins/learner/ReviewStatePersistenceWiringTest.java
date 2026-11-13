package com.dwinovo.numen.plugins.learner;

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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-13: the real maybeNudge consumer must honor durable clocks across restart, not only helpers. */
class ReviewStatePersistenceWiringTest {
    private static final UUID C = UUID.fromString("a43d753e-790d-472b-8d5b-dc18ac0afbc0");

    @TempDir Path dir;
    private MemoQueue queue;
    private final AtomicInteger sent = new AtomicInteger();

    @BeforeEach void setUp() throws Exception {
        clearMap("LAST_REVIEW_NUDGE");
        clearMap("LAST_AUTO_REVIEW_AT");
        clearMap("NUDGE_UNDELIVERABLE_UNTIL");
        clearMap("REVIEW_FAIL_STREAK");
        LearnerAutoIntake.install(dir);
        Path memoFile = dir.resolve("learner-memos-" + C + ".json");
        queue = new MemoQueue(memoFile);
        long now = System.currentTimeMillis();
        for (int i = 0; i < 3; i++) {
            assertTrue(queue.append(new Memo("wire-" + i, "p", "death", "t", "{}", now)));
        }
        LearnerAutoIntake.installQueueResolverForTest(id -> queue);
        LearnerAutoIntake.installNudgeSenderForTest((id, text) -> { sent.incrementAndGet(); return true; });
    }

    @AfterEach void tearDown() throws Exception {
        LearnerAutoIntake.installQueueResolverForTest(null);
        LearnerAutoIntake.installNudgeSenderForTest(null);
        clearMap("LAST_REVIEW_NUDGE");
        clearMap("LAST_AUTO_REVIEW_AT");
        clearMap("NUDGE_UNDELIVERABLE_UNTIL");
        clearMap("REVIEW_FAIL_STREAK");
    }

    @SuppressWarnings("unchecked")
    private static void clearMap(String name) throws Exception {
        Field f = LearnerAutoIntake.class.getDeclaredField(name);
        f.setAccessible(true);
        ((java.util.Map<?, ?>) f.get(null)).clear();
    }

    @Test void restartWithinTenMinutesDoesNotResendOrRefreshTheClock() throws Exception {
        long old = System.currentTimeMillis() - 5 * 60_000L;
        assertTrue(LearnerPlugin.rememberNudge(dir, C, old));
        LearnerAutoIntake.maybeNudgeReview();
        LearnerPlugin.ReviewStateRead read = LearnerPlugin.readReviewTimes(dir, C);
        assertAll(
                () -> assertEquals(0, sent.get(), "an old 5-minute reminder must not be re-sent after restart"),
                () -> assertTrue(read.known()),
                () -> assertEquals(old, read.times().lastNudgeAt(), "a skipped nudge must not refresh the durable clock"));
    }

    @Test void restartAfterTenMinutesSendsOnceAndRefreshesTheClock() throws Exception {
        long old = System.currentTimeMillis() - 11 * 60_000L;
        assertTrue(LearnerPlugin.rememberNudge(dir, C, old));
        long before = System.currentTimeMillis();
        LearnerAutoIntake.maybeNudgeReview();
        LearnerPlugin.ReviewStateRead read = LearnerPlugin.readReviewTimes(dir, C);
        assertAll(
                () -> assertEquals(1, sent.get(), "an 11-minute unanswered reminder is due exactly once"),
                () -> assertTrue(read.known()),
                () -> assertTrue(read.times().lastNudgeAt() >= before, "a sent nudge must persist its new time"));
    }

    @Test void malformedCompanionShapeIsUnknownAndWritesAreRefused() throws Exception {
        Path f = dir.resolve("learner-review-state.json");
        String bad = "{\"last_review_at\":\"x\",\"companions\":{\"" + C + "\":\"bad\"}}";
        Files.writeString(f, bad, StandardCharsets.UTF_8);
        assertFalse(LearnerPlugin.readReviewTimes(dir, C).known(), "non-object companion node is UNKNOWN");
        assertFalse(LearnerPlugin.rememberNudge(dir, C, 123L), "writer must refuse a malformed state");
        assertEquals(bad, Files.readString(f, StandardCharsets.UTF_8), "refused write must keep the original bytes");
    }
}
