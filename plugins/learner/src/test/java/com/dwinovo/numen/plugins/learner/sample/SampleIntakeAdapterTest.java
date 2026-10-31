package com.dwinovo.numen.plugins.learner.sample;

import com.dwinovo.numen.plugins.learner.core.FeedbackEvent;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三条 intake 口 → 样本候选的映射（160 §6/§7）。
 *
 * <p>钉死：缺同伴就跳过而不是编造；时间戳解析不了要降级并留警告；
 * 归因按固定顺序拼（否则跨进程指纹对不上）；世界/维度能从观测兜底。
 */
class SampleIntakeAdapterTest {

    private static final long NOW = 1_700_000_000_000L;

    private static SampleIntakeAdapter.Envelope env(String source) {
        return new SampleIntakeAdapter.Envelope("run-1", "g1", "3", "w1", "overworld", source, NOW);
    }

    private static FeedbackEvent event(String evId, String ts, Map<String, Object> obs) {
        return new FeedbackEvent(evId, "gen-1", ts, "death", "DEATH",
                Map.of("companion", "c1"), obs, null);
    }

    private static Map<String, Object> obs(String... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void 完整事件映射出全部字段() {
        FeedbackEvent ev = event("ev-1", "1700000000000",
                obs("companionId", "c-9", "deathCauseId", "zombie", "reason", "被围住"));
        SampleIntakeAdapter.Mapped m = SampleIntakeAdapter.fromEvent(ev, env(SampleIntakeAdapter.SRC_MANUAL));

        assertTrue(m.present());
        assertTrue(m.warnings().isEmpty(), "完整事件不该有警告：" + m.warnings());
        SampleAdmission.Candidate c = m.candidate();
        assertEquals("c-9", c.companionId(), "companionId 优先于 companion");
        assertEquals("death", c.triggerKind());
        assertEquals("ev-1", c.evidenceRef());
        assertEquals("run-1", c.runId());
        assertEquals("w1", c.worldId());
        assertEquals("overworld", c.dimension());
        assertEquals(SampleIntakeAdapter.SRC_MANUAL, c.source());
        assertEquals(1_700_000_000_000L, c.occurredAt());
        assertTrue(c.causeSignature().contains("deathCauseId=zombie"));
    }

    @Test
    void 缺同伴直接跳过而不是编一个() {
        FeedbackEvent ev = new FeedbackEvent("ev-1", "g", "1", "death", "DEATH", Map.of(), Map.of(), null);
        SampleIntakeAdapter.Mapped m = SampleIntakeAdapter.fromEvent(ev, env(SampleIntakeAdapter.SRC_MANUAL));
        assertFalse(m.present(), "没有同伴的样本无法回链，宁可不要");
        assertTrue(m.warnings().contains("missing_companion"));
    }

    @Test
    void 无归因记字面量并留警告() {
        FeedbackEvent ev = event("ev-1", "1700000000000", obs("companionId", "c1"));
        SampleIntakeAdapter.Mapped m = SampleIntakeAdapter.fromEvent(ev, env(SampleIntakeAdapter.SRC_AUTO));
        assertEquals(SampleIntakeAdapter.UNATTRIBUTED, m.candidate().causeSignature());
        assertTrue(m.warnings().contains("no_attribution"), "无归因必须能被统计出来");
    }

    @Test
    void 归因顺序固定_字段插入顺序不影响指纹() {
        FeedbackEvent a = event("ev-1", "1700000000000",
                obs("companionId", "c1", "deathCauseId", "zombie", "reason", "r"));
        FeedbackEvent b = event("ev-1", "1700000000000",
                obs("reason", "r", "companionId", "c1", "deathCauseId", "zombie"));
        assertEquals(SampleIntakeAdapter.causeOf(a), SampleIntakeAdapter.causeOf(b),
                "顺序不固定则跨进程指纹对不上，重启后同一次事故会变两条");
    }

    @Test
    void 时间戳三种形态() {
        assertEquals(1700000000000L, SampleIntakeAdapter.parseTimestamp("1700000000000"));
        assertEquals(0L, SampleIntakeAdapter.parseTimestamp("1970-01-01T00:00:00Z"));
        assertEquals(-1L, SampleIntakeAdapter.parseTimestamp("昨天下午"));
        assertEquals(-1L, SampleIntakeAdapter.parseTimestamp(""));
        assertEquals(-1L, SampleIntakeAdapter.parseTimestamp("-5"));
    }

    @Test
    void 解析不了的时间戳用登记时刻并警告() {
        FeedbackEvent ev = event("ev-1", "昨天", obs("companionId", "c1", "reason", "r"));
        SampleIntakeAdapter.Mapped m = SampleIntakeAdapter.fromEvent(ev, env(SampleIntakeAdapter.SRC_MANUAL));
        assertEquals(NOW, m.candidate().occurredAt(), "兜底用登记时刻");
        assertTrue(m.warnings().contains("ts_unparsed"));
    }

    @Test
    void 世界与维度能从观测兜底() {
        SampleIntakeAdapter.Envelope noWorld =
                new SampleIntakeAdapter.Envelope("run-1", "", "", "", "", "manual_intake", NOW);
        FeedbackEvent ev = event("ev-1", "1700000000000",
                obs("companionId", "c1", "worldId", "w-obs", "dimension", "the_nether", "reason", "r"));
        SampleIntakeAdapter.Mapped m = SampleIntakeAdapter.fromEvent(ev, noWorld);
        assertEquals("w-obs", m.candidate().worldId());
        assertEquals("the_nether", m.candidate().dimension());
    }

    @Test
    void 工具失败口用最后发生时间与工具错误形态做归因() {
        SampleIntakeAdapter.Mapped m = SampleIntakeAdapter.fromToolFailure(
                "c1", "craft", "missing_ingredient", 3, NOW - 500, "ev-f1",
                env(SampleIntakeAdapter.SRC_TOOL_FAILURE));
        assertTrue(m.present());
        SampleAdmission.Candidate c = m.candidate();
        assertEquals(SampleIntakeAdapter.SRC_TOOL_FAILURE, c.triggerKind());
        assertEquals("craft|missing_ingredient", c.causeSignature());
        assertEquals(NOW - 500, c.occurredAt(), "窗口是「最近仍在发生」，用最后发生时间");
        assertEquals("ev-f1", c.evidenceRef());
        assertEquals(SampleIntakeAdapter.SRC_TOOL_FAILURE, c.source());
    }

    @Test
    void 工具失败缺证据引用要留警告() {
        SampleIntakeAdapter.Mapped m = SampleIntakeAdapter.fromToolFailure(
                "c1", "craft", "x", 3, NOW, "", env(SampleIntakeAdapter.SRC_TOOL_FAILURE));
        assertTrue(m.present(), "缺证据引用不等于不能成样");
        assertTrue(m.warnings().contains("missing_evidence_ref"));
        assertNotEquals(0, m.warnings().size());
    }

    @Test
    void 空输入不抛异常只跳过() {
        assertFalse(SampleIntakeAdapter.fromEvent(null, env("x")).present());
        assertFalse(SampleIntakeAdapter.fromToolFailure("", "craft", "x", 1, NOW, "ev", env("x")).present());
        assertFalse(SampleIntakeAdapter.fromToolFailure("c1", " ", "x", 1, NOW, "ev", env("x")).present());
    }
}
