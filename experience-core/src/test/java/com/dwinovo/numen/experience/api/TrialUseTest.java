package com.dwinovo.numen.experience.api;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** G6c 判据 C2：从事件归约出试用账（不新增台账）。 */
class TrialUseTest {

    private static TrialUse u(String ref, long ms, TrialUse.Outcome o) {
        return new TrialUse(ref, ms, o);
    }

    @Test
    void derivesCountsAndLastUse() {
        List<TrialUse> uses = List.of(
                u("exp:a", 100L, TrialUse.Outcome.SUCCESS),
                u("exp:a", 300L, TrialUse.Outcome.FAILURE),
                u("exp:b", 200L, TrialUse.Outcome.SUCCESS));
        TrialStats s = TrialUse.derive("exp:a", uses);
        assertEquals(2, s.uses());
        assertEquals(1, s.successes());
        assertEquals(1, s.failures());
        assertEquals(300L, s.lastUsedMillis(), "取该条目自己的最后一次，不是全局最后");
    }

    @Test
    void inconclusiveCountsAsUseButNotAsOutcome() {
        List<TrialUse> uses = List.of(
                u("exp:a", 1L, TrialUse.Outcome.SUCCESS),
                u("exp:a", 2L, TrialUse.Outcome.INCONCLUSIVE),
                u("exp:a", 3L, TrialUse.Outcome.INCONCLUSIVE));
        TrialStats s = TrialUse.derive("exp:a", uses);
        assertEquals(3, s.uses());
        assertEquals(1, s.successes());
        assertEquals(0, s.failures());
        assertEquals(2, s.inconclusive());
        assertEquals(TrialVerdict.Decision.KEEP_TRIAL,
                TrialVerdict.decide(s), "三次里只有一次成功、零失败但样本不够 → 继续试用");
    }

    @Test
    void unknownRefIsEmpty() {
        assertEquals(TrialStats.EMPTY, TrialUse.derive("exp:missing",
                List.of(u("exp:a", 1L, TrialUse.Outcome.SUCCESS))));
    }

    @Test
    void nullsAreTolerated() {
        assertEquals(TrialStats.EMPTY, TrialUse.derive(null, List.of()));
        assertEquals(TrialStats.EMPTY, TrialUse.derive("exp:a", null));
    }

    @Test
    void allFailuresLeadToSuspend() {
        TrialStats s = TrialUse.derive("exp:a", List.of(
                u("exp:a", 1L, TrialUse.Outcome.FAILURE),
                u("exp:a", 2L, TrialUse.Outcome.FAILURE),
                u("exp:a", 3L, TrialUse.Outcome.FAILURE)));
        assertEquals(TrialVerdict.Decision.SUSPEND, TrialVerdict.decide(s));
    }
}
