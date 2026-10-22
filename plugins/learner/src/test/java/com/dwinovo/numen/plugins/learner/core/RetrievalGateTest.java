package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G6b 闸门（{@link RetrievalGate}）：只有「写经验」这条动作要被「先检索」卡住。
 *
 * <p>用真 JSON 过 {@link Verdict#parse} 构造判定 —— 这样测的是**真实链路**，
 * 而不是手工 new 一个理想对象。
 */
class RetrievalGateTest {

    private static Verdict v(String actions, String retrieval) {
        String json = "{\"memo_id\":\"m1\",\"actions\":" + actions
                + ",\"confidence\":0.7,\"reasoning\":\"r\""
                + (retrieval == null ? "" : ",\"retrieval\":" + retrieval) + "}";
        Verdict parsed = Verdict.parse("?", json);
        assertNotNull(parsed, "自检：JSON 应该能解析出判定");
        return parsed;
    }

    @Test
    void writingAnExperienceWithoutRetrievalIsRejected() {
        Verdict verdict = v("[\"WRITE_EXPERIENCE\"]", null);
        String problem = RetrievalGate.problem(verdict);
        assertNotNull(problem, "写经验却没给 retrieval 段，必须拒");
        assertTrue(problem.contains("retrieval"), "拒因要说清缺什么：" + problem);
    }

    @Test
    void newIsRejectedWhenSomethingSimilarAlreadyExists() {
        Verdict verdict = v("[\"WRITE_EXPERIENCE\"]",
                "{\"decision\":\"NEW\",\"reason\":\"再写一条\",\"hits\":["
                        + "{\"entry_ref\":\"exp:a\",\"title\":\"挖矿\",\"why\":\"同标题\",\"high_similarity\":true}]}");
        assertNotNull(RetrievalGate.problem(verdict), "有高相似命中还 NEW，必须拒");
    }

    @Test
    void reuseWithAHitIsAllowed() {
        Verdict verdict = v("[\"WRITE_EXPERIENCE\"]",
                "{\"decision\":\"REUSE\",\"reason\":\"同主题已有\",\"hits\":["
                        + "{\"entry_ref\":\"exp:a\",\"title\":\"挖矿\",\"why\":\"同标题\",\"high_similarity\":true}]}");
        assertNull(RetrievalGate.problem(verdict));
    }

    @Test
    void newWithOnlyWeakHitsIsAllowed() {
        Verdict verdict = v("[\"WRITE_EXPERIENCE\"]",
                "{\"decision\":\"NEW\",\"reason\":\"只是措辞像，机制不同\",\"hits\":["
                        + "{\"entry_ref\":\"exp:b\",\"title\":\"挖矿\",\"why\":\"同 tag\",\"high_similarity\":false}]}");
        assertNull(RetrievalGate.problem(verdict));
    }

    @Test
    void actionsThatDoNotWriteExperienceAreNotGated() {
        assertNull(RetrievalGate.problem(v("[\"NO_ACTION\"]", null)),
                "不写经验就不该被「先检索」卡住");
        assertNull(RetrievalGate.problem(v("[\"NEEDS_HUMAN\"]", null)));
    }
}
