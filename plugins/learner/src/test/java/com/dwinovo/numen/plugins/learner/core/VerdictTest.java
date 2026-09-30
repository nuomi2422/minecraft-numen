package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 判定解析契约 —— 2026-09-29 Codex 审出的 P1 就在这一层。
 *
 * <p>原实现批量解析时用占位 id("?")，而 {@code Verdict.parse} 又不读 JSON 里的
 * memo_id，导致下游无法把判定对回备忘录，复盘工具只能整批 commit → 删掉没被
 * 复盘到的条目。修复后：memo_id 必须来自 JSON；缺失/不合法一律不产出判定。
 */
class VerdictTest {

    @Test
    void readsMemoIdFromJson() {
        String json = """
                {"memo_id":"m-42","actions":["WRITE_EXPERIENCE"],"confidence":0.8,
                 "reasoning":"reuse","experience_draft":"d","ac_script_draft":"",
                 "rewritten_query":["iron ore","mining"]}""";
        Verdict v = Verdict.parse("SHOULD-BE-IGNORED", json);
        assertNotNull(v);
        assertEquals("m-42", v.memoId(), "memo_id 必须来自 JSON，不能用调用方传的占位符");
        assertEquals(List.of(Verdict.Action.WRITE_EXPERIENCE), v.actions());
    }

    /** 缺失 memo_id 且没有合法 fallback 时不产出判定（上游会把这批当未判定 → restore）。 */
    @Test
    void rejectsVerdictWithoutMemoId() {
        String json = """
                {"actions":["NO_ACTION"],"confidence":0.5}""";
        assertNull(Verdict.parse("", json), "没有 memo_id 就不能产出会错配的判定");
    }

    @Test
    void fallsBackWhenExplicitlyGivenMemoId() {
        String json = """
                {"actions":["NO_ACTION"],"confidence":0.5}""";
        Verdict v = Verdict.parse("m-7", json);
        assertNotNull(v);
        assertEquals("m-7", v.memoId());
    }

    @Test
    void parsesMultipleActions() {
        String json = """
                {"memo_id":"m-1","actions":["WRITE_EXPERIENCE","USE_AC","USE_CARRIER"],
                 "confidence":0.6}""";
        Verdict v = Verdict.parse("", json);
        assertNotNull(v);
        assertEquals(3, v.actions().size());
        assertTrue(v.actions().contains(Verdict.Action.USE_AC));
    }

    @Test
    void rejectsUnknownAction() {
        String json = """
                {"memo_id":"m-1","actions":["DO_MAGIC"],"confidence":0.9}""";
        assertNull(Verdict.parse("", json), "未知动作不能被当成合法判定");
    }

    @Test
    void clampsConfidence() {
        Verdict hi = Verdict.parse("", """
                {"memo_id":"m-1","actions":["NO_ACTION"],"confidence":5}""");
        assertNotNull(hi);
        assertEquals(1.0, hi.confidence(), 1e-9);

        Verdict lo = Verdict.parse("", """
                {"memo_id":"m-1","actions":["NO_ACTION"],"confidence":-3}""");
        assertNotNull(lo);
        assertEquals(0.0, lo.confidence(), 1e-9);
    }

    /** 真实模型经常把 JSON 包在 ```json 围栏里。 */
    @Test
    void toleratesMarkdownFence() {
        String raw = """
                ```json
                {"memo_id":"m-9","actions":["USE_AC"],"confidence":0.7}
                ```""";
        Verdict v = Verdict.parse("", raw);
        assertNotNull(v, "带围栏的 JSON 也应能解析");
        assertEquals("m-9", v.memoId());
    }

    @Test
    void rejectsGarbage() {
        assertNull(Verdict.parse("m-1", "I'm sorry, I cannot do that"));
        assertNull(Verdict.parse("m-1", ""));
        assertNull(Verdict.parse("m-1", null));
        assertNull(Verdict.parse("m-1", "[1,2,3]"), "数组不是合法判定对象");
    }

    @Test
    void actionParseIsCaseInsensitive() {
        assertEquals(Verdict.Action.SELF_COMPILE, Verdict.Action.parse("self_compile"));
        assertEquals(Verdict.Action.NEEDS_HUMAN, Verdict.Action.parse("  Needs_Human  "));
        assertNull(Verdict.Action.parse("nope"));
        assertNull(Verdict.Action.parse(null));
    }

    @Test
    void toJsonKeepsActions() {
        Verdict v = Verdict.parse("", """
                {"memo_id":"m-3","actions":["USE_CARRIER"],"confidence":0.4,"rewritten_query":["hp"]}""");
        assertNotNull(v);
        String json = v.toJson();
        assertTrue(json.contains("m-3"), json);
        assertTrue(json.contains("USE_CARRIER"), json);
    }
}
