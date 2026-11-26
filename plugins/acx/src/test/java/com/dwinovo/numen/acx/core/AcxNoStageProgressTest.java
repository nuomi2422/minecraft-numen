package com.dwinovo.numen.acx.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AcxNoStageProgressTest {
    @Test
    void heartbeatsAndElapsedTimeDoNotExtendNoProgressBudget() {
        AcxNoStageProgress state = new AcxNoStageProgress(0);
        Map<String, Object> facts = facts("navigate", 7);
        assertFalse(state.observe(facts, 0).expired());
        assertFalse(state.observe(heartbeat("navigate", 7, 1_000), 1_000).expired());
        assertFalse(state.observe(heartbeat("navigate", 7, 29_000), 29_000).expired());
        AcxNoStageProgress.Result expired = state.observe(heartbeat("navigate", 7, 30_000), 30_000);
        assertTrue(expired.expired());
        assertEquals(30_000L, expired.details().get("last_effective_progress_ms"));
        assertEquals("body_active", expired.details().get("wait_reason"));
    }

    @Test
    void missingFactsNeverCountAsProgressOrRefreshTheBudget() {
        AcxNoStageProgress state = new AcxNoStageProgress(0, facts("navigate", 1));
        assertFalse(state.observe(Map.of(), 29_000).expired());
        assertTrue(state.observe(Map.of(), 30_000).expired());
        assertEquals("navigate", state.details(30_000).get("stage"));
    }

    @Test
    void onlyProducerSequenceOrStageChangeRefreshesProgress() {
        AcxNoStageProgress state = new AcxNoStageProgress(0);
        assertFalse(state.observe(facts("navigate", 1), 0).expired());
        assertFalse(state.observe(facts("navigate", 2), 29_000).expired());
        assertFalse(state.observe(facts("navigate", 2), 30_000).expired());
        assertFalse(state.observe(facts("dig", 2), 59_000).expired());
        assertTrue(state.observe(facts("dig", 2), 89_000).expired());
    }

    @Test
    void continuousDeclaredProgressStillHitsHardObservationCap() {
        AcxNoStageProgress state = new AcxNoStageProgress(0);
        for (int second = 0; second <= AcxNoStageProgress.MAX_OBSERVATION_MS / 1_000; second++) {
            AcxNoStageProgress.Result result = state.observe(facts("navigate", second), second * 1_000L);
            assertEquals(second == AcxNoStageProgress.MAX_OBSERVATION_MS / 1_000, result.expired());
        }
    }

    @Test
    void producerBudgetIsBoundedAndUnknownFactsExpireWithoutBecomingFailure() {
        AcxNoStageProgress bounded = new AcxNoStageProgress(0);
        Map<String, Object> facts = facts("scan", 1);
        facts.put("no_progress_budget_ms", 5_000L);
        assertFalse(bounded.observe(facts, 0).expired());
        assertTrue(bounded.observe(facts, 5_000).expired());

        AcxNoStageProgress unknown = new AcxNoStageProgress(0);
        assertFalse(unknown.observe(null, 0).expired());
        assertTrue(unknown.observe(Map.of(), AcxNoStageProgress.DEFAULT_NO_PROGRESS_MS).expired());
    }

    private static Map<String, Object> facts(String stage, int sequence) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stage", stage);
        out.put("progress_seq", sequence);
        out.put("wait_reason", "body_active");
        return out;
    }

    private static Map<String, Object> heartbeat(String stage, int sequence, long observedAt) {
        Map<String, Object> out = facts(stage, sequence);
        out.put("observed_at", observedAt);
        out.put("elapsed_ms", observedAt);
        out.put("task_id", "t" + observedAt);
        return out;
    }
}
