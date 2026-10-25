package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 跨会话 T3 重建（81 号 §14.1 Q4：两个身份分开）的契约测试。
 *
 * <p>要钉死的语义：
 * <ol>
 *   <li><b>问题指纹</b>：三次独立的同指纹死亡 → 重放历史后 seenBefore = 3
 *       （跨重启累计，正是修 session_only 的那个缺口）；</li>
 *   <li><b>事件身份</b>：同一 eventId 重放两遍 → 只算一次（轮转/重复行不虚增）；</li>
 *   <li>成功/中性事件不进计数（只有失败族稀释"重复失败"信号）；</li>
 *   <li>不同同伴各自数各自（LC2：不许跨伙伴放大）。</li>
 * </ol>
 */
class T3SeenRebuildTest {

    private static FeedbackEvent death(String eventId, String companion, String attacker) {
        return new FeedbackEvent(eventId, "live@1", "2026-10-25T09:00:00Z", "death", "death",
                Map.of("companion", companion),
                Map.of("companionId", companion, "deathKind", "mob", "deathAttacker", attacker,
                        "reason", "mob", "task", "subtask-x"),
                null);
    }

    private static FeedbackEvent completed(String eventId, String companion) {
        return new FeedbackEvent(eventId, "live@1", "2026-10-25T09:00:00Z", "subtask_completed", "task",
                Map.of("companion", companion), Map.of("companionId", companion), null);
    }

    @Test
    void threeIndependentDeathsAccumulateAcrossRestart() {
        T3Seen.resetForTest();
        String c = "e502d17b-0000-0000-0000-000000000001";
        int counted = T3Seen.rebuildFrom(List.of(
                death("d1", c, "Zombie"), death("d2", c, "Zombie"), death("d3", c, "Zombie")));
        assertEquals(3, counted);
        // 同类死亡（同 attacker 同任务）应聚成同一指纹并累计三次
        String fp = CandidateGate.fingerprint("death",
                Map.of("companionId", c, "deathKind", "mob", "deathAttacker", "Zombie",
                        "reason", "mob", "task", "subtask-x"));
        assertEquals(3, T3Seen.seenBefore(c, fp), "三次独立死亡=模式，不是意外");
    }

    @Test
    void replayedSameEventCountsOnce() {
        T3Seen.resetForTest();
        String c = "e502d17b-0000-0000-0000-000000000001";
        FeedbackEvent ev = death("d1", c, "Zombie");
        int counted = T3Seen.rebuildFrom(List.of(ev, ev, ev));   // 轮转副本/重复行
        assertEquals(1, counted, "同一 eventId 重放只算一次（事件身份）");
    }

    @Test
    void nonFailureEventsDoNotDilute() {
        T3Seen.resetForTest();
        String c = "e502d17b-0000-0000-0000-000000000001";
        int counted = T3Seen.rebuildFrom(List.of(
                death("d1", c, "Zombie"),
                completed("s1", c), completed("s2", c)));
        assertEquals(1, counted, "成功事件不进 T3 计数");
    }

    @Test
    void companionsAreIsolated() {
        T3Seen.resetForTest();
        String a = "e502d17b-0000-0000-0000-000000000001";
        String b = "e44fe5f8-0000-0000-0000-000000000002";
        T3Seen.rebuildFrom(List.of(death("d1", a, "Zombie"), death("d2", b, "Zombie"), death("d3", b, "Zombie")));
        String fpA = CandidateGate.fingerprint("death",
                Map.of("companionId", a, "deathKind", "mob", "deathAttacker", "Zombie",
                        "reason", "mob", "task", "subtask-x"));
        String fpB = CandidateGate.fingerprint("death",
                Map.of("companionId", b, "deathKind", "mob", "deathAttacker", "Zombie",
                        "reason", "mob", "task", "subtask-x"));
        assertEquals(1, T3Seen.seenBefore(a, fpA), "A 的死亡不数进 B（LC2）");
        assertEquals(2, T3Seen.seenBefore(b, fpB));
    }
}
