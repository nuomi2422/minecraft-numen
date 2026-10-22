package com.dwinovo.numen.experience.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G6c 判据 C3/C4：试用判定规则（样本不足不猜；失败不被平均掩盖）。 */
class TrialVerdictTest {

    private static TrialStats s(int uses, int ok, int bad) {
        return new TrialStats(uses, ok, bad, 0L);
    }

    @Test
    void noUseMeansNoEvidence() {
        assertEquals(TrialVerdict.Decision.KEEP_TRIAL, TrialVerdict.decide(TrialStats.EMPTY));
        assertEquals(TrialVerdict.Decision.KEEP_TRIAL, TrialVerdict.decide(null));
    }

    @Test
    void c4_oneWinOneLossIsNotPublishWorthy() {
        // 这正是 C4：1 成功 1 失败不能被判成「可以转正」
        assertEquals(TrialVerdict.Decision.KEEP_TRIAL, TrialVerdict.decide(s(2, 1, 1)),
                "1 成 1 败是样本不足，不是证据");
    }

    @Test
    void enoughSamplesAllWinningSuggestsPublish() {
        assertEquals(TrialVerdict.Decision.SUGGEST_PUBLISH, TrialVerdict.decide(s(3, 3, 0)));
        assertEquals(TrialVerdict.Decision.SUGGEST_PUBLISH, TrialVerdict.decide(s(5, 4, 1)));
    }

    @Test
    void neverWorkedIsSuspended() {
        assertEquals(TrialVerdict.Decision.SUSPEND, TrialVerdict.decide(s(3, 0, 3)),
                "用了三次一次没成 → 停");
    }

    @Test
    void tiesStayInTrial() {
        assertEquals(TrialVerdict.Decision.KEEP_TRIAL, TrialVerdict.decide(s(6, 3, 3)),
                "成功不多于失败就不该转正");
    }

    @Test
    void inconclusiveRunsDoNotCountAsSuccess() {
        // 4 次里 2 次取消：成功 1 / 失败 1 —— 不能因为「用得久」就当成功
        TrialStats stats = s(4, 1, 1);
        assertEquals(2, stats.inconclusive());
        assertEquals(TrialVerdict.Decision.KEEP_TRIAL, TrialVerdict.decide(stats));
    }

    @Test
    void contradictoryStatsKeepTheFailures() {
        // 数据自相矛盾（成功+失败 > 使用次数）时：失败数保留，成功数被裁到装得下为止
        TrialStats stats = new TrialStats(4, 9, 4, 0L);
        assertEquals(4, stats.failures(), "失败是最不能丢的数");
        assertEquals(0, stats.successes(), "成功数要裁到 uses - failures");
        assertTrue(stats.successes() + stats.failures() <= stats.uses());
        assertEquals(TrialVerdict.Decision.SUSPEND, TrialVerdict.decide(stats),
                "样本够但一次没成 → 停（即便原始数据里写满了成功）");
    }

    @Test
    void explainIsReadable() {
        String text = TrialVerdict.explain(s(2, 1, 1), TrialVerdict.Decision.KEEP_TRIAL);
        assertTrue(text.contains("样本不足"), text);
    }
}
