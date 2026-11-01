package com.dwinovo.numen.plugins.learner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-02：试用常态化（auto_trial）的纯判定。
 *
 * <p>只在「契约 accepted + 源可信 + maturity≥OBSERVED」时自动进 TRIAL；
 * 发布（PUBLISHED）仍是显式决策 —— ExperienceVerifyTool 的注释明确「不自动发布」。
 */
class LearnerReviewPromotionPolicyTest {

    @Test
    void promotesOnlyWhenContractAcceptedAndSourceTrustedAndObserved() {
        assertTrue(LearnerReviewTool.shouldAutoTrial(true, true, "OBSERVED"));
        assertTrue(LearnerReviewTool.shouldAutoTrial(true, true, "VERIFIED"));
        assertFalse(LearnerReviewTool.shouldAutoTrial(true, true, "ATTEMPTED"), "ATTEMPTED 不自动试用");
        assertFalse(LearnerReviewTool.shouldAutoTrial(false, true, "OBSERVED"), "契约没过不试用");
        assertFalse(LearnerReviewTool.shouldAutoTrial(true, false, "OBSERVED"), "源不可信不试用");
        assertFalse(LearnerReviewTool.shouldAutoTrial(true, true, ""));
        assertFalse(LearnerReviewTool.shouldAutoTrial(true, true, null));
        assertFalse(LearnerReviewTool.shouldAutoTrial(true, true, "DRAFT"), "DRAFT 不是 maturity 档位，不试用");
    }

    @Test
    void jsonFieldReadsDataLevelFields() {
        String reply = "{\"success\":true,\"message\":\"ok\",\"data\":{\"id\":\"execution|x\",\"maturity\":\"OBSERVED\"}}";
        assertEquals("execution|x", LearnerReviewTool.jsonField(reply, "id"));
        assertEquals("OBSERVED", LearnerReviewTool.jsonField(reply, "maturity"));
        assertEquals("", LearnerReviewTool.jsonField(reply, "missing"));
        assertEquals("", LearnerReviewTool.jsonField("not json", "id"));
        assertEquals("", LearnerReviewTool.jsonField(null, "id"));
    }
}
