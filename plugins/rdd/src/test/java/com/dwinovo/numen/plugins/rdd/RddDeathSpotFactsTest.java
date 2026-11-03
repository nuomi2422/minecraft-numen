package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.RddDeathLedger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-02：死亡→规划的唯一入口 knownFailures。
 * 之前规划侧只塞「上一次子步骤不可执行」，死亡原因永远进不了 experience 检索。
 */
class RddDeathSpotFactsTest {

    private static RddDeathLedger.Death death(int seq, String at, String cause) {
        return new RddDeathLedger.Death(seq, 1000L * seq, 5000L * seq, at, 3,
                RddDeathLedger.State.UNVERIFIED, "", null, cause);
    }

    @Test
    void noDeathsMeansNoFailures() {
        assertTrue(RddDeathSpotFacts.knownFailures(List.of()).isEmpty());
        assertTrue(RddDeathSpotFacts.knownFailures(null).isEmpty());
    }

    @Test
    void recentDeathCarriesCauseAndCoordinate() {
        var out = RddDeathSpotFacts.knownFailures(List.of(death(47, "38, 38, 34", "lava")));
        assertEquals(1, out.size(), out.toString());
        assertTrue(out.get(0).contains("lava"), out.get(0));
        assertTrue(out.get(0).contains("38, 38, 34"), out.get(0));
    }

    @Test
    void repeatDeathAtSameSpotIsCalledOut() {
        var out = RddDeathSpotFacts.knownFailures(List.of(
                death(43, "15, 61, 106", "fall"),
                death(47, "15, 62, 107", "lava")));
        assertTrue(out.stream().anyMatch(s -> s.contains("同一地点已死 2 次")), out.toString());
    }

    @Test
    void unknownCauseStaysUnknown() {
        var out = RddDeathSpotFacts.knownFailures(List.of(death(1, "1, 2, 3", "")));
        assertTrue(out.get(0).contains("unknown"), out.get(0));
    }

    /**
     * 2026-11-03（P0-1）：确证测试致死不能进 knownFailures，否则规划侧会把人工验证当成真实失败依据。
     */
    @Test
    void testKillsDoNotBecomeKnownFailures() {
        RddDeathLedger.clearAll();
        UUID id = UUID.randomUUID();
        RddDeathLedger.record(id, 1000L, 5_000L, "15, 61, 106", 3, null, "fall");
        RddDeathLedger.markTestKill(id);
        RddDeathLedger.record(id, 1100L, 5_100L, "15, 62, 107", 3, null, "genericKill");

        var out = RddDeathSpotFacts.knownFailuresFor(id);
        assertTrue(out.stream().anyMatch(s -> s.contains("fall")), "自然死亡要保留：" + out);
        assertTrue(out.stream().noneMatch(s -> s.contains("同一地点已死 2 次")),
                "测试致死不能和自然死亡凑成“同点重复”：" + out);
    }

    @Test
    void failuresAreCapped() {
        var list = new java.util.ArrayList<RddDeathLedger.Death>();
        for (int i = 0; i < 8; i++) list.add(death(i + 1, (100 + i) + ", 64, 100", "fall"));
        var out = RddDeathSpotFacts.knownFailures(list);
        assertTrue(out.size() <= RddDeathSpotFacts.MAX_FAILURES, "上限 " + RddDeathSpotFacts.MAX_FAILURES + "，实际 " + out.size());
    }
}