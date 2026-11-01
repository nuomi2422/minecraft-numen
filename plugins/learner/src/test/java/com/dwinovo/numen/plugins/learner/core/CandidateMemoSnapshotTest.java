package com.dwinovo.numen.plugins.learner.core;

import com.dwinovo.numen.plugins.learner.contract.ReviewContractAdapter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-02（F9 正向）：instrumentation 事件的 memo 快照必须带 window_first。
 *
 * <p>真实 death memo 之前 0/36 带 window_first —— 复盘契约拿不到 event: 证据，
 * 死亡经验永远 claim_without_evidence。这里用真实 FeedbackEvent 构造，不是合成字符串。
 */
class CandidateMemoSnapshotTest {

    private static FeedbackEvent deathEvent() {
        return new FeedbackEvent("instr-1793560000000-1", "live@1", "2026-11-02T03:00:00Z",
                "death", "death", Map.of("task", "primary-1"),
                Map.of("deathCauseId", "lava", "deathKind", "lava"), null);
    }

    @Test
    void instrumentationSnapshotCarriesWindowFirst() {
        String snap = CandidateMemo.snapshot(deathEvent());
        assertTrue(snap.contains("window_first=instr-1793560000000-1"), snap);
        assertEquals("instr-1793560000000-1", ReviewContractAdapter.windowFirstOf(snap));
    }

    @Test
    void deathSnapshotIsTrustedButWriterlessToolFailureIsNot() {
        String snap = CandidateMemo.snapshot(deathEvent());
        assertTrue(ReviewContractAdapter.evidenceAllowed(snap, "death"),
                "instrumentation 事件由服务端日志产生，可信");
        assertFalse(ReviewContractAdapter.evidenceAllowed(snap, "tool_failure"),
                "tool_failure 仍按写者标记判定，缺标签不可信");
    }
}
