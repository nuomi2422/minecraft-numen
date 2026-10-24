package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LC2（78 号 §7 判据）：同失败重复 100 次不产生 100 次学习请求。
 * 钉死：冷却窗口内只放行第一次；窗口过后放行；伙伴隔离；T3 计数不受节流影响（分工测试）；
 * 读数如实。
 */
class StormGuardTest {

    private static final String A = "companion-a";
    private static final String B = "companion-b";
    private static final String FP = "death|deathcauseid=generickill";

    @BeforeEach
    void clean() {
        StormGuard.resetForTest();
    }

    @Test
    void 同指纹风暴只放行第一次() {
        // 78 号 LC2 判据的直译：100 次重复只入队 1 次
        int allowed = 0;
        for (int i = 0; i < 100; i++) {
            if (StormGuard.admit(A, FP, 1_000_000L + i).allow()) {
                allowed++;
            }
        }
        assertEquals(1, allowed, "100 条同失败风暴必须只放行 1 条候选");
        assertEquals(99, StormGuard.suppressedTotal(A, FP), "99 条被抑制，计数如实不静默");
    }

    @Test
    void 冷却窗口过后放行_不是永远封杀() {
        assertTrue(StormGuard.admit(A, FP, 1_000_000L).allow(), "第一次放行");
        assertFalse(StormGuard.admit(A, FP, 1_000_000L + 1_000).allow(), "窗口内抑制");
        long after = 1_000_000L + StormGuard.COOLDOWN_MILLIS + 1;
        assertTrue(StormGuard.admit(A, FP, after).allow(),
                "冷却过后同一问题再出现要放行——节流不是封杀，问题复发值得再看一眼");
        assertEquals(1, StormGuard.suppressedTotal(A, FP));
    }

    @Test
    void 伙伴之间互不影响() {
        // 78 号 §2.2：A 的风暴不许替 B 挡路
        assertTrue(StormGuard.admit(A, FP, 1_000_000L).allow());
        assertFalse(StormGuard.admit(A, FP, 1_000_500L).allow());
        assertTrue(StormGuard.admit(B, FP, 1_000_600L).allow(),
                "B 同伴遇到同一问题是它自己的第一次，必须入队");
        assertEquals(1, StormGuard.suppressedTotal(A, FP));
        assertEquals(0, StormGuard.suppressedTotal(B, FP));
    }

    @Test
    void 不同指纹互不影响() {
        assertTrue(StormGuard.admit(A, "death|deathcauseid=generickill", 1_000_000L).allow());
        assertTrue(StormGuard.admit(A, "death|deathcauseid=zombie", 1_000_000L).allow(),
                "不同问题是不同的问题，不许互相节流");
    }

    @Test
    void 抑制判定带余量读数() {
        StormGuard.admit(A, FP, 1_000_000L);
        StormGuard.Ruling r = StormGuard.admit(A, FP, 1_000_000L + 60_000);
        assertFalse(r.allow());
        assertEquals("SUPPRESSED_COOLDOWN", r.code());
        assertEquals(StormGuard.COOLDOWN_MILLIS - 60_000, r.windowMillis(),
                "「还要多久才放行」是排障要用的数");
        assertEquals(1, r.suppressedTotal());
    }

    @Test
    void 全局抑制总数如实累计() {
        StormGuard.admit(A, FP, 1_000_000L);
        StormGuard.admit(A, FP, 1_000_100L);
        StormGuard.admit(A, FP, 1_000_200L);
        StormGuard.admit(B, "loop_detected|task=mine", 1_000_000L);
        StormGuard.admit(B, "loop_detected|task=mine", 1_000_100L);
        assertEquals(3, StormGuard.suppressedGrandTotal(), "跨键跨伙伴的抑制总数要能对账");
    }

    @Test
    void 节流不影响T3计数_模式照常生长() {
        // 分工的钉子：风暴抑制是入队层面的，seenBefore 计数照走 ——
        // 第 100 次出现的候选（若在窗口外放行）看到的 T3 是真实的 99 次历史
        String fp = CandidateGate.fingerprint("death", Map.of("deathCauseId", "genericKill"));
        for (int i = 0; i < 3; i++) {
            StormGuard.admit(A, fp, 1_000_000L + i);
        }
        CandidateGate.Decision d = CandidateGate.evaluate("death",
                Map.of("deathCauseId", "genericKill"), 2);
        assertTrue(d.enqueue(), "T3 的模式判定不归 StormGuard 管");
        assertEquals("T2", d.primaryTrigger());
        boolean t3Match = d.triggers().stream()
                .filter(t -> "T3".equals(t.id()))
                .anyMatch(t -> t.status() == CandidateGate.Status.MATCH);
        assertTrue(t3Match, "第 3 次出现的 T3 模式判定必须照常成立");
    }
}
