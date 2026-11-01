package com.dwinovo.numen.plugins.learner.contract;

import com.dwinovo.numen.plugins.learner.core.Verdict;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-02：复盘产物的证据引用 + F9 源写者闸。
 *
 * <p>旧口径下适配器把证据留空，contract_gate 10/10 判 claim_without_evidence，
 * 经验永远到不了 TRIAL；但直接接 window_first 又会给 client 注入假失败开门，
 * 所以证据只对「写明来路且不是 client」的 memo 放行。
 */
class ReviewContractAdapterTest {

    private static Verdict verdict() {
        String json = "{"
                + "\"memo_id\":\"m-1\","
                + "\"actions\":[\"WRITE_EXPERIENCE\"],"
                + "\"confidence\":0.7,"
                + "\"reasoning\":\"工具连续失败三次\","
                + "\"experience\":{\"mechanism\":\"m\",\"preconditions\":\"p\",\"failureConditions\":\"f\","
                + "\"observableSignal\":\"o\",\"derivation\":\"d\",\"efficiency\":\"e\",\"evidence\":\"ev\","
                + "\"experienceType\":\"FAILURE\"}}";
        return Verdict.parse("m-1", json);
    }

    @Test
    void experienceArtifactCarriesPreconditionsAndWindowFirst() {
        var sub = ReviewContractAdapter.toSubmission(verdict(), "m-1", Submission.Mode.INTERNAL, "tools-123-0");
        var a = sub.artifacts().get(0);
        assertEquals("p", a.triggerConditions().get(0), "preconditions 抬成 triggerConditions");
        assertEquals(1, a.evidence().size(), "源 memo 的 window_first 作为证据");
        assertEquals("tools-123-0", a.evidence().get(0).id());
    }

    @Test
    void carrierArtifactCarriesItsOwnTriggerAndEvidence() {
        String carrier = "{\"name\":\"c\",\"triggerConditions\":[\"night\"],"
                + "\"evidence\":[\"event:e-1\"]}";
        String json = "{\"memo_id\":\"m-2\",\"actions\":[\"USE_CARRIER\"],\"confidence\":0.6,"
                + "\"reasoning\":\"r\",\"carrier_draft\":" + javaEscaped(carrier) + "}";
        var v = Verdict.parse("m-2", json);
        var sub = ReviewContractAdapter.toSubmission(v, "m-2", Submission.Mode.INTERNAL, "e-1");
        var a = sub.artifacts().get(0);
        assertEquals(1, a.triggerConditions().size());
        assertEquals(1, a.evidence().size());
    }

    @Test
    void sideTaskArtifactCarriesItsOwnTriggerAndEvidence() {
        String side = "{\"type_id\":\"learner_x\",\"when\":{},\"nudge\":\"d\","
                + "\"triggerConditions\":[\"is_dark\"],\"evidence\":[\"event:e-2\"]}";
        String json = "{\"memo_id\":\"m-3\",\"actions\":[\"SIDE_TASK\"],\"confidence\":0.6,"
                + "\"reasoning\":\"r\",\"side_task_draft\":" + javaEscaped(side) + "}";
        var v = Verdict.parse("m-3", json);
        var sub = ReviewContractAdapter.toSubmission(v, "m-3", Submission.Mode.INTERNAL, "e-2");
        var a = sub.artifacts().get(0);
        assertEquals(1, a.triggerConditions().size());
        assertEquals(1, a.evidence().size());
    }

    /** 把一段 JSON 变成可嵌入另一个 JSON 字符串字面量的形式（只处理引号/反斜杠就够）。 */
    private static String javaEscaped(String inner) {
        return "\"" + inner.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    @Test
    void windowFirstBecomesEventEvidenceRef() {
        var sub = ReviewContractAdapter.toSubmission(verdict(), "m-1", Submission.Mode.INTERNAL, "tools-123-0");
        var ev = sub.review().claims().get(0).evidence();
        assertEquals(1, ev.size());
        assertEquals(EvidenceRef.Kind.EVENT, ev.get(0).kind());
        assertEquals("tools-123-0", ev.get(0).id());
    }

    @Test
    void missingWindowFirstKeepsEvidenceEmpty() {
        var sub = ReviewContractAdapter.toSubmission(verdict(), "m-1", Submission.Mode.INTERNAL);
        assertTrue(sub.review().claims().get(0).evidence().isEmpty());
    }

    @Test
    void evidenceGuardRejectsClientAndUnknownWriters() {
        assertTrue(ReviewContractAdapter.evidenceAllowed("journaled_by=server-async, window_first=tools-1-0"));
        assertTrue(ReviewContractAdapter.evidenceAllowed("journaled_by=client-local, window_first=tools-1-0"));
        assertFalse(ReviewContractAdapter.evidenceAllowed("journaled_by=client, window_first=tools-1-0"),
                "client 注入行不得带证据");
        assertFalse(ReviewContractAdapter.evidenceAllowed("journaled_by=unknown, window_first=tools-1-0"),
                "写者 unknown 等于不知道来源，不得带证据");
        assertFalse(ReviewContractAdapter.evidenceAllowed("window_first=tools-1-0"),
                "legacy memo 没有写者标记：不知道来源 ≠ 可信");
        assertFalse(ReviewContractAdapter.evidenceAllowed(null));
    }

    @Test
    void clientInjectedMemoNeverGetsEvidence() {
        String snapshot = "items=1, tool=look_around, class=x, journaled_by=client, window_first=tools-5-0";
        String wf = ReviewContractAdapter.evidenceAllowed(snapshot)
                ? ReviewContractAdapter.windowFirstOf(snapshot) : "";
        var sub = ReviewContractAdapter.toSubmission(verdict(), "m-1", Submission.Mode.INTERNAL, wf);
        assertTrue(sub.review().claims().get(0).evidence().isEmpty(),
                "client 注入 memo 不得因 window_first 过契约门");
    }

    @Test
    void instrumentationStagesAreTrustedWithoutWriterLabel() {
        // death/loop 等 instrumentation 事件由服务端日志产生，不走 client 写者的 tool_result 路径。
        assertTrue(ReviewContractAdapter.evidenceAllowed("items=3, window_first=instr-1-0", "death"));
        assertTrue(ReviewContractAdapter.evidenceAllowed("window_first=instr-2-0", "loop_detected"));
        assertFalse(ReviewContractAdapter.evidenceAllowed("window_first=instr-3-0", "tool_failure"),
                "tool_failure 仍按写者标记判定，缺标签不可信");
        assertTrue(ReviewContractAdapter.evidenceAllowed(
                "journaled_by=server-async, window_first=tools-4-0", "tool_failure"));
    }

    @Test
    void acDraftTriggerAndEvidenceAreLiftedNotInvented() {
        String draft = "{\"name\":\"low_hp_disengage\",\"version\":\"1\","
                + "\"triggerConditions\":[\"hp<6 且有敌意实体 nearby\"],"
                + "\"evidence\":[\"event:tools-1793562339505-1\",\"not-a-ref\"]}";
        var trig = ReviewContractAdapter.listField(draft, "triggerConditions");
        assertEquals(1, trig.size());
        assertEquals("hp<6 且有敌意实体 nearby", trig.get(0));
        var ev = ReviewContractAdapter.evidenceField(draft);
        assertEquals(1, ev.size(), "非法引用被丢弃，不伪造");
        assertEquals("tools-1793562339505-1", ev.get(0).id());
        assertEquals("EVENT", ev.get(0).kind().name());
        // 草稿没写这两个键 → 空表（契约会如实判 missing_field，而不是被补造）
        assertTrue(ReviewContractAdapter.listField("{\"name\":\"x\"}", "triggerConditions").isEmpty());
        assertTrue(ReviewContractAdapter.evidenceField("not json").isEmpty());
    }

    @Test
    void windowFirstOfParsesSnapshot() {
        assertEquals("tools-9-1", ReviewContractAdapter.windowFirstOf("items=3, window_first=tools-9-1, window_last=tools-10-0"));
        assertEquals("", ReviewContractAdapter.windowFirstOf("no pointer"));
    }
}
