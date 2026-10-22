package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G6b 判据 Z1–Z3：先检索再写。
 *
 * <ul>
 *   <li>Z1：hits / decision / reason 三样齐全才算合法决策。</li>
 *   <li>Z2：REUSE/REVISE 要指向检索到的条目；NEW 不许在有高相似命中时出现。</li>
 *   <li>Z3：同主题第二次（库里那条被标 highSimilarity）只能 REUSE/REVISE，不能 NEW。</li>
 * </ul>
 */
class RetrievalDecisionTest {

    private static RetrievalDecision.Hit hit(String ref, boolean high) {
        return new RetrievalDecision.Hit(ref, "标题 " + ref, "同标题", high);
    }

    @Test
    void parseReadsTheWholeSegment() {
        String json = """
                {"retrieval":{"decision":"REVISE","reason":"已有条目缺失败条件",
                 "hits":[{"entry_ref":"exp:a","title":"挖矿","why":"同标题","high_similarity":true}]}}
                """;
        RetrievalDecision d = RetrievalDecision.parse(
                JsonParser.parseString(json).getAsJsonObject());
        assertNotNull(d);
        assertEquals(RetrievalDecision.Kind.REVISE, d.decision());
        assertEquals(1, d.hits().size());
        assertEquals("exp:a", d.hits().get(0).entryRef());
        assertTrue(d.hits().get(0).highSimilarity());
        assertNull(d.problem(), "三样齐全且 REVISE 指向命中条目 → 合法");
    }

    @Test
    void missingSegmentIsNullNotFake() {
        RetrievalDecision d = RetrievalDecision.parse(
                JsonParser.parseString("{\"memo_id\":\"m1\"}").getAsJsonObject());
        assertNull(d, "没有 retrieval 段就是没有，不许伪造一个空决策");
    }

    @Test
    void z1_missingPiecesAreRejected() {
        assertNotNull(new RetrievalDecision(List.of(hit("exp:a", false)), null, "理由").problem(),
                "缺 decision 要拒");
        assertNotNull(new RetrievalDecision(List.of(hit("exp:a", false)),
                RetrievalDecision.Kind.NEW, "  ").problem(), "缺 reason 要拒");
    }

    @Test
    void z2_reuseMustPointAtSomethingRetrieved() {
        RetrievalDecision empty = new RetrievalDecision(List.of(),
                RetrievalDecision.Kind.REUSE, "复用已有");
        assertNotNull(empty.problem(), "REUSE 但 hits 为空 = 引用了一条没检索到的条目");

        RetrievalDecision ok = new RetrievalDecision(List.of(hit("exp:a", true)),
                RetrievalDecision.Kind.REUSE, "同主题，直接复用");
        assertNull(ok.problem());
    }

    @Test
    void z2_newIsBlockedByHighSimilarityHit() {
        RetrievalDecision bad = new RetrievalDecision(List.of(hit("exp:a", true)),
                RetrievalDecision.Kind.NEW, "我觉得这是新的");
        assertNotNull(bad.problem(), "有高相似命中还判 NEW，必须拒");

        RetrievalDecision ok = new RetrievalDecision(List.of(hit("exp:b", false)),
                RetrievalDecision.Kind.NEW, "只是措辞相近，机制不同");
        assertNull(ok.problem(), "只有低相似命中时，NEW 是允许的（但理由要写清）");
    }

    @Test
    void z3_secondCandidateOfSameTopicCannotBeNew() {
        // 第二次进来：库里那条（第一次写的）被检索侧标成 highSimilarity
        List<RetrievalDecision.Hit> hits = List.of(hit("exp:first", true));
        assertNotNull(new RetrievalDecision(hits, RetrievalDecision.Kind.NEW, "再写一条").problem(),
                "同主题第二次必须落 REUSE/REVISE");
        assertNull(new RetrievalDecision(hits, RetrievalDecision.Kind.REVISE, "补失败条件").problem());
    }

    @Test
    void verdictCarriesTheDecision() {
        String json = """
                {"memo_id":"m1","actions":["WRITE_EXPERIENCE"],"confidence":0.8,"reasoning":"r",
                 "retrieval":{"decision":"NEW","reason":"库里没有同类",
                   "hits":[]}}
                """;
        Verdict v = Verdict.parse("?", json);
        assertNotNull(v, "合法 JSON 应该解析出判定");
        assertNotNull(v.retrieval(), "G6b：retrieval 段要能进 Verdict");
        assertTrue(v.retrieval().valid(), "无命中 + NEW + 有理由 → 合法");
    }

    @Test
    void oldFormatStillParsesWithoutRetrieval() {
        String json = """
                {"memo_id":"m1","actions":["NO_ACTION"],"confidence":0.1,"reasoning":"r"}
                """;
        Verdict v = Verdict.parse("?", json);
        assertNotNull(v);
        assertNull(v.retrieval(), "老格式没有 retrieval 段 → null（收不收由闸门决定）");
        assertFalse(v.retrieval() != null);
    }
}
