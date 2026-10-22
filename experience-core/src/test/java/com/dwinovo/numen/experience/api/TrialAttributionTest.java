package com.dwinovo.numen.experience.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** G6c：呈现 → 任务结局的归因（保守口径：宁可算不清，也不虚报成功）。 */
class TrialAttributionTest {

    private static TrialAttribution.Presentation p(String ref, long ms) {
        return new TrialAttribution.Presentation(ref, ms);
    }

    private static TrialAttribution.TaskOutcome t(long ms, boolean ok) {
        return new TrialAttribution.TaskOutcome(ms, ok);
    }

    @Test
    void successWithinWindowIsAttributed() {
        List<TrialUse> uses = TrialAttribution.attribute(
                List.of(p("exp:a", 1000L)),
                List.of(t(2000L, true)));
        assertEquals(1, uses.size());
        assertEquals(TrialUse.Outcome.SUCCESS, uses.get(0).outcome());
        assertEquals("exp:a", uses.get(0).entryRef());
    }

    @Test
    void failureWithinWindowIsFailureNotSilence() {
        List<TrialUse> uses = TrialAttribution.attribute(
                List.of(p("exp:a", 1000L)),
                List.of(t(2000L, false)));
        assertEquals(TrialUse.Outcome.FAILURE, uses.get(0).outcome(), "失败必须记下来，不许被吞");
    }

    @Test
    void taskBeforePresentationIsNotAttributed() {
        List<TrialUse> uses = TrialAttribution.attribute(
                List.of(p("exp:a", 5000L)),
                List.of(t(1000L, true)));
        assertEquals(TrialUse.Outcome.INCONCLUSIVE, uses.get(0).outcome());
    }

    @Test
    void taskOutsideWindowIsInconclusive() {
        List<TrialUse> uses = TrialAttribution.attribute(
                List.of(p("exp:a", 0L)),
                List.of(t(TrialAttribution.WINDOW_MILLIS + 1, true)));
        assertEquals(TrialUse.Outcome.INCONCLUSIVE, uses.get(0).outcome(),
                "窗口外的事不能算成它的功劳");
    }

    @Test
    void oneOutcomeIsClaimedByOnlyOnePresentation() {
        // 两次呈现 + 一次成功：只有离它最近的那次能拿到这个成功，另一次是 INCONCLUSIVE
        List<TrialUse> uses = TrialAttribution.attribute(
                List.of(p("exp:a", 0L), p("exp:b", 900L)),
                List.of(t(1000L, true)));
        assertEquals(2, uses.size());
        assertEquals(TrialUse.Outcome.SUCCESS, uses.get(0).outcome(), "第一次呈现先认领");
        assertEquals(TrialUse.Outcome.INCONCLUSIVE, uses.get(1).outcome(), "同一成功不许被第二条再吃一次");
    }

    @Test
    void emptyInputsAreEmpty() {
        assertEquals(0, TrialAttribution.attribute(null, null).size());
        assertEquals(0, TrialAttribution.attribute(List.of(), List.of(t(1L, true))).size());
    }

    @Test
    void blankRefIsNotCredited() {
        List<TrialUse> uses = TrialAttribution.attribute(
                List.of(new TrialAttribution.Presentation("", 0L)),
                List.of(t(1000L, true)));
        assertEquals(TrialUse.Outcome.INCONCLUSIVE, uses.get(0).outcome(), "没有 ref 就没有归因对象");
    }

    @Test
    void wholeChainProducesAVerdict() {
        List<TrialAttribution.Presentation> ps = List.of(
                p("exp:a", 0L), p("exp:a", 10_000L), p("exp:a", 20_000L));
        List<TrialAttribution.TaskOutcome> ts = List.of(
                t(5_000L, true), t(15_000L, true), t(25_000L, false));
        List<TrialUse> uses = TrialAttribution.attribute(ps, ts);
        TrialStats stats = TrialUse.derive("exp:a", uses);
        assertEquals(3, stats.uses());
        assertEquals(2, stats.successes());
        assertEquals(1, stats.failures());
        assertEquals(TrialVerdict.Decision.SUGGEST_PUBLISH, TrialVerdict.decide(stats));
    }
}
