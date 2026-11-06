package com.dwinovo.numen.plugins.learner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-06（外部学习者，实机 02:31:58）：复盘 429 必须分型。
 *
 * <p>反例：容量类 429 被当成额度耗尽，锁 30 分钟闸门，工具提示“换账号”，
 * 短退避内也不会真正重发；正例：GoUsageLimitError / weekly 才是额度。
 */
class LearnerReviewQuotaClassifyTest {

    @Test
    void weeklyUsageLimitIsRealQuota() {
        String why = "LLM_FAILED: HTTP 429: {\"error\":{\"type\":\"GoUsageLimitError\","
                + "\"message\":\"Go usage limit exceeded\"},"
                + "\"metadata\":{\"limitName\":\"weekly\"}}";
        assertTrue(LearnerReviewTool.isQuotaExhausted(why));
    }

    @Test
    void capacityPrefillIsNotQuota() {
        String why = "LLM_FAILED: HTTP 429: {\"error\":{\"type\":\"server_error\","
                + "\"message\":\"Upstream request failed: [prefill_cap_refused] the cell is at capacity;"
                + " retry shortly (after 5 s)\"}}";
        assertFalse(LearnerReviewTool.isQuotaExhausted(why));
    }

    @Test
    void unknown429DoesNotClaimQuota() {
        assertFalse(LearnerReviewTool.isQuotaExhausted("LLM_FAILED: HTTP 429: rate limited"));
        assertFalse(LearnerReviewTool.isQuotaExhausted(""));
        assertFalse(LearnerReviewTool.isQuotaExhausted(null));
    }

    @Test
    void otherFailuresAreNotQuota() {
        assertFalse(LearnerReviewTool.isQuotaExhausted("LLM_TIMEOUT after 300s"));
        assertFalse(LearnerReviewTool.isQuotaExhausted("Verdict.parse: bad json"));
    }
}
