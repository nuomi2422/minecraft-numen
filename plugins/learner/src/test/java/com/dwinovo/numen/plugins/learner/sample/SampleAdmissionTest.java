package com.dwinovo.numen.plugins.learner.sample;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 158 §6 去重判定 + 159 §3 剧本 1 的判定面。
 *
 * <p>钉死：同一次事故多次上报只追加 occurrence；同证据不重复计次；归因不同时保留独立样本并标记，
 * 不许合并、也不许当全新事件从零复盘；同伴/目标/世界/时间桶任一变化都不许误合并。
 */
class SampleAdmissionTest {

    private static final long BUCKET = 5 * 60 * 1000L;
    private static final long T0 = 1_700_000_000_000L;

    private static SampleAdmission.Candidate cand(String companion, String cause, String goal,
                                                  String world, String dim, long at, String evidence) {
        return new SampleAdmission.Candidate(companion, "death", "被僵尸打死", cause,
                "run-1", goal, "1", world, dim, at, evidence, "instrumentation");
    }

    private static SampleAdmission.Candidate death(long at, String evidence) {
        return cand("c1", "zombie", "g1", "w1", "overworld", at, evidence);
    }

    @Test
    void 无既有样本时新建并带一条出现() {
        SampleAdmission.Decision d = SampleAdmission.decide(death(T0, "ev-1"), null, null, BUCKET, T0, "s-1");
        assertEquals(SampleAdmission.Outcome.NEW, d.outcome());
        assertEquals(1, d.sample().occurrenceCount());
        assertEquals(SampleState.OPEN, d.sample().state());
        assertFalse(d.sample().fingerprint().isBlank(), "样本必须带指纹，否则重启后无法幂等");
        assertFalse(d.sample().possibleDuplicate(), "无对照时不许标记疑似重复");
        assertEquals("", d.matchedSampleId());
    }

    @Test
    void 强指纹命中只追加出现且保留最早最近() {
        LearningSample first = SampleAdmission.decide(death(T0, "ev-1"), null, null, BUCKET, T0, "s-1").sample();
        long later = T0 + 30_000L;
        SampleAdmission.Decision d2 = SampleAdmission.decide(death(later, "ev-2"), first, null, BUCKET, later, "s-x");

        assertEquals(SampleAdmission.Outcome.MERGED, d2.outcome());
        assertEquals("s-1", d2.matchedSampleId());
        assertEquals(2, d2.sample().occurrenceCount());
        assertEquals(T0, d2.sample().earliestEvidenceAt(), "最早证据必须保住");
        assertEquals(later, d2.sample().latestEvidenceAt(), "最近证据必须前移");

        long latest = later + 10_000L;
        LearningSample third = SampleAdmission.decide(death(latest, "ev-3"), d2.sample(), null, BUCKET, latest, "s-x").sample();
        assertEquals(3, third.occurrenceCount());
        assertEquals(T0, third.earliestEvidenceAt());
        assertEquals(latest, third.latestEvidenceAt());
    }

    @Test
    void 同一条证据重复投递不重复计次() {
        LearningSample first = SampleAdmission.decide(death(T0, "ev-1"), null, null, BUCKET, T0, "s-1").sample();
        SampleAdmission.Decision again = SampleAdmission.decide(death(T0, "ev-1"), first, null, BUCKET, T0 + 5, "s-x");
        assertEquals(SampleAdmission.Outcome.MERGED, again.outcome());
        assertEquals(1, again.sample().occurrenceCount(), "intake 重试不是复发，不许把次数刷高");
    }

    @Test
    void 归因不同保留独立样本并标记疑似重复() {
        LearningSample first = SampleAdmission.decide(death(T0, "ev-1"), null, null, BUCKET, T0, "s-1").sample();
        SampleAdmission.Candidate other = cand("c1", "lava", "g1", "w1", "overworld", T0 + 1000L, "ev-2");

        SampleAdmission.Decision d = SampleAdmission.decide(other, null, first, BUCKET, T0, "s-2");

        assertEquals(SampleAdmission.Outcome.POSSIBLE_DUPLICATE, d.outcome());
        assertTrue(d.created(), "疑似重复仍是一条独立样本");
        assertTrue(d.sample().possibleDuplicate());
        assertEquals("s-1", d.sample().possibleDuplicateOf());
        assertTrue(d.sample().relatedSampleIds().contains("s-1"), "关联用于排障跳转");
        assertEquals(1, d.sample().occurrenceCount(), "别人的证据不许并进来");
    }

    @Test
    void 不同目标不误合并() {
        LearningSample first = SampleAdmission.decide(death(T0, "ev-1"), null, null, BUCKET, T0, "s-1").sample();
        SampleAdmission.Candidate otherGoal = cand("c1", "zombie", "g2", "w1", "overworld", T0, "ev-2");

        SampleAdmission.Decision d = SampleAdmission.decide(otherGoal, null, null, BUCKET, T0, "s-2");
        assertEquals(SampleAdmission.Outcome.NEW, d.outcome(), "目标版本不同的同一现象是两个样本");
    }

    @Test
    void 不同世界或维度不误合并() {
        SampleAdmission.Candidate otherWorld = cand("c1", "zombie", "g1", "w2", "overworld", T0, "ev-2");
        assertEquals(SampleAdmission.Outcome.NEW,
                SampleAdmission.decide(otherWorld, null, null, BUCKET, T0, "s-2").outcome());

        SampleAdmission.Candidate otherDim = cand("c1", "zombie", "g1", "w1", "the_nether", T0, "ev-2");
        assertEquals(SampleAdmission.Outcome.NEW,
                SampleAdmission.decide(otherDim, null, null, BUCKET, T0, "s-2").outcome());
    }

    @Test
    void 不同同伴不误合并() {
        SampleAdmission.Candidate other = cand("c2", "zombie", "g1", "w1", "overworld", T0, "ev-2");
        assertEquals(SampleAdmission.Outcome.NEW,
                SampleAdmission.decide(other, null, null, BUCKET, T0, "s-2").outcome());
    }

    @Test
    void 跨时间桶是同名事故的新样本() {
        LearningSample first = SampleAdmission.decide(death(T0, "ev-1"), null, null, BUCKET, T0, "s-1").sample();
        long muchLater = T0 + BUCKET * 3;
        SampleAdmission.Decision d = SampleAdmission.decide(death(muchLater, "ev-2"), null, null, BUCKET, muchLater, "s-2");
        assertEquals(SampleAdmission.Outcome.NEW, d.outcome());
    }

    @Test
    void 缺同伴或触发类型直接拒绝() {
        assertThrows(IllegalArgumentException.class,
                () -> new SampleAdmission.Candidate("", "death", "", "", "", "", "", "", "", T0, "e", "s"));
        assertThrows(IllegalArgumentException.class,
                () -> new SampleAdmission.Candidate("c1", "", "", "", "", "", "", "", "", T0, "e", "s"));
    }

    @Test
    void 新建样本不给id必须抛() {
        assertThrows(IllegalArgumentException.class,
                () -> SampleAdmission.decide(death(T0, "ev-1"), null, null, BUCKET, T0, " "));
    }
}
