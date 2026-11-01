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
    void windowFirstOfParsesSnapshot() {
        assertEquals("tools-9-1", ReviewContractAdapter.windowFirstOf("items=3, window_first=tools-9-1, window_last=tools-10-0"));
        assertEquals("", ReviewContractAdapter.windowFirstOf("no pointer"));
    }
}
