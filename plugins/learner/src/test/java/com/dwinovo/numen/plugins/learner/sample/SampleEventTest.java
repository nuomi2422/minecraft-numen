package com.dwinovo.numen.plugins.learner.sample;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 160 §4 统一事件外壳的样本层部分。
 *
 * <p>钉死：三类事件各归各位（创建 / 又出现 / 重复投递被去重）；事件必带 sampleId；
 * 缺 runId 时显式写 UNKNOWN 而不是编一个；eventId 可复算（离线回放要求）。
 */
class SampleEventTest {

    private static final long BUCKET = 5 * 60 * 1000L;
    private static final long T0 = 1_700_000_000_000L;

    private static SampleAdmission.Candidate cand(long at, String evidence, String run) {
        return new SampleAdmission.Candidate("c1", "death", "被僵尸打死", "zombie",
                run, "g1", "1", "w1", "overworld", at, evidence, "instrumentation");
    }

    @Test
    void 新建样本发创建事件且带齐关联键() {
        SampleAdmission.Decision d = SampleAdmission.decide(cand(T0, "ev-1", "run-7"), null, null, BUCKET, T0, "s-1");
        assertEquals(1, d.events().size());
        SampleEvent e = d.events().get(0);
        assertEquals(SampleEvent.SAMPLE_CREATED, e.eventType());
        assertEquals("run-7", e.runId(), "runId 必须从候选带过来");
        assertEquals("s-1", e.sampleId(), "160 §3：样本链事件必须带 sampleId");
        assertFalse(e.occurrenceId().isBlank(), "occurrenceId 是 160 §3 的最小关联键之一");
        assertEquals(SampleEvent.SCHEMA_VERSION, e.schemaVersion());
        assertTrue(e.evidenceRefs().contains("ev-1"));
        assertTrue(e.hasRun());
        assertFalse(e.eventId().isBlank());
    }

    @Test
    void 又来一次发出现事件_同证据重复发去重事件() {
        LearningSample first = SampleAdmission.decide(cand(T0, "ev-1", "r"), null, null, BUCKET, T0, "s-1").sample();

        SampleAdmission.Decision again = SampleAdmission.decide(cand(T0 + 1000, "ev-2", "r"), first, null, BUCKET, T0, "s-x");
        assertEquals(SampleEvent.SAMPLE_OCCURRENCE_ADDED, again.events().get(0).eventType());

        SampleAdmission.Decision dup = SampleAdmission.decide(cand(T0, "ev-1", "r"), first, null, BUCKET, T0, "s-x");
        assertEquals(SampleEvent.SAMPLE_DEDUPED, dup.events().get(0).eventType(),
                "同一条证据重投不是复发，必须落成去重事件");
    }

    @Test
    void 疑似重复同时发创建与去重两条事件() {
        LearningSample first = SampleAdmission.decide(cand(T0, "ev-1", "r"), null, null, BUCKET, T0, "s-1").sample();
        SampleAdmission.Candidate lava = new SampleAdmission.Candidate("c1", "death", "被岩浆烧死", "lava",
                "r", "g1", "1", "w1", "overworld", T0 + 500, "ev-2", "instrumentation");

        SampleAdmission.Decision d = SampleAdmission.decide(lava, null, first, BUCKET, T0, "s-2");
        List<SampleEvent> evs = d.events();
        assertEquals(2, evs.size());
        assertEquals(SampleEvent.SAMPLE_CREATED, evs.get(0).eventType());
        assertEquals(SampleEvent.SAMPLE_DEDUPED, evs.get(1).eventType());
        assertTrue(evs.stream().allMatch(e -> "s-2".equals(e.sampleId())), "两条都指向新样本");
    }

    @Test
    void 缺runId写UNKNOWN而不是编一个() {
        SampleAdmission.Decision d = SampleAdmission.decide(cand(T0, "ev-1", ""), null, null, BUCKET, T0, "s-1");
        SampleEvent e = d.events().get(0);
        assertEquals(SampleEvent.UNKNOWN, e.runId());
        assertFalse(e.hasRun(), "门诊指标要能把「回不到 run」算出来");
    }

    @Test
    void eventId可复算_同一次登记重复执行得到同一个id() {
        SampleAdmission.Decision a = SampleAdmission.decide(cand(T0, "ev-1", "r"), null, null, BUCKET, T0, "s-1");
        SampleAdmission.Decision b = SampleAdmission.decide(cand(T0, "ev-1", "r"), null, null, BUCKET, T0, "s-1");
        assertEquals(a.events().get(0).eventId(), b.events().get(0).eventId(),
                "离线回放要求事件 id 由内容决定，不能是随机 UUID");
    }

    @Test
    void 没有sampleId的事件直接拒绝() {
        assertThrows(IllegalArgumentException.class, () -> new SampleEvent(
                "ev-1", SampleEvent.SAMPLE_CREATED, T0, "r", "learner:internal",
                "", "oc-1", "OPEN", List.of(), SampleEvent.SCHEMA_VERSION));
    }

    @Test
    void 事件可序列化为JSONL一行() {
        SampleAdmission.Decision d = SampleAdmission.decide(cand(T0, "ev-1", "r"), null, null, BUCKET, T0, "s-1");
        String json = d.events().get(0).toJson().toString();
        assertTrue(json.contains("\"eventType\":\"SAMPLE_CREATED\""));
        assertTrue(json.contains("\"sampleId\":\"s-1\""));
        assertFalse(json.contains("\n"), "一行一个事件，不许带换行");
    }
}
