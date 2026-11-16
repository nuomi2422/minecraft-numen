package com.dwinovo.numen.core.task.chain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MobDefenseChainTest {

    @Test
    void reflexFightHasAStrictBoundedOwnershipWindow() {
        long started = 4_000L;
        assertFalse(MobDefenseChain.reflexFightBudgetExhausted(started,
                started + MobDefenseChain.MAX_REFLEX_FIGHT_TICKS - 1));
        assertTrue(MobDefenseChain.reflexFightBudgetExhausted(started,
                started + MobDefenseChain.MAX_REFLEX_FIGHT_TICKS));
    }

    @Test
    void tickScansOnceBeforeFightButStartLogKeepsFreshObservation() throws Exception {
        // Source wiring regression only; not a simulated Minecraft combat test.
        var root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isDirectory(root.resolve("core/common/src/main/java"))) {
            root = root.getParent();
        }
        assertTrue(root != null, "must locate candidate source tree");
        String source = java.nio.file.Files.readString(root.resolve(
                "core/common/src/main/java/com/dwinovo/numen/core/task/chain/MobDefenseChain.java"));
        String canRun = between(source, "public boolean canRun(NumenPlayer companion) {", "public TaskState tick(NumenPlayer companion) {");
        String tick = between(source, "public TaskState tick(NumenPlayer companion) {", "private void begin(NumenPlayer companion) {");
        String begin = between(source, "private void begin(NumenPlayer companion) {", "private static final long NO_DEADLINE");
        org.junit.jupiter.api.Assertions.assertEquals(1, occurrences(canRun, "dangersNear(companion)"));
        org.junit.jupiter.api.Assertions.assertEquals(1, occurrences(tick, "dangersNear(companion)"));
        org.junit.jupiter.api.Assertions.assertEquals(1, occurrences(begin, "dangersNear(companion)"));
        assertTrue(tick.contains("if (threat)"));
        assertTrue(tick.contains("if (!threat)"));
        assertTrue(begin.indexOf("fight.start(companion)") < begin.indexOf("dangersNear(companion)"));
    }

    private static String between(String source, String start, String end) {
        int a = source.indexOf(start), b = source.indexOf(end, a + start.length());
        assertTrue(a >= 0 && b > a, "source method boundaries must exist");
        return source.substring(a, b);
    }

    private static int occurrences(String source, String needle) {
        return (source.length() - source.replace(needle, "").length()) / needle.length();
    }

    @Test
    void unstartedOrFutureFightNeverTimesOut() {
        assertFalse(MobDefenseChain.reflexFightBudgetExhausted(Long.MIN_VALUE, 10_000L));
        assertFalse(MobDefenseChain.reflexFightBudgetExhausted(10_000L, 9_999L));
    }
}
