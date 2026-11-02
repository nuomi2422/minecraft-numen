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
    void hugeSnapshotStillCarriesWindowFirst() {
        // death47 复盘暴露：快照超长时早期 return 把 window_first 截掉，死亡经验永远缺证据。
        java.util.Map<String, Object> big = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 120; i++) {
            big.put("context.inventorySlot" + i, "minecraft:item" + i);
        }
        big.put("deathCauseId", "onFire");
        FeedbackEvent ev = new FeedbackEvent("instr-1793613312224-9", "live@1",
                "2026-11-02T09:55:00Z", "death", "death", java.util.Map.of("task", "primary"), big, null);
        String snap = CandidateMemo.snapshot(ev);
        assertTrue(snap.contains("…[cut]"), "确实被截断");
        assertTrue(snap.contains("window_first=instr-1793613312224-9"),
                "截断后仍必须带轨迹锚点：" + snap.substring(Math.max(0, snap.length() - 120)));
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
