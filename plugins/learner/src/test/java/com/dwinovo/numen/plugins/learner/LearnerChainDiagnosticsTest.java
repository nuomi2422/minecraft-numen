package com.dwinovo.numen.plugins.learner;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07 C0: each chain failure has an explicit event/retryable state; never says learned. */
class LearnerChainDiagnosticsTest {
    private static void assertNoLearned(LearnerChainDiagnostics.Diag d) {
        assertFalse(d.message().toLowerCase().contains("learned"), d.message());
        assertTrue(d.message().contains("no entry stored") || d.message().contains("NOT stored"), d.message());
    }

    @Test void reviewFailureIsRetryableAndDiagnosed() {
        var d = LearnerChainDiagnostics.reviewFailed("llm timeout");
        assertEquals("auto_review_failed", d.event());
        assertEquals("FAILED", d.outcome());
        assertTrue(d.retryable());
        assertNoLearned(d);
    }

    @Test void storeFailureIsRetryableAndDiagnosed() {
        var d = LearnerChainDiagnostics.storeFailed("IOException");
        assertEquals("experience_store_failed", d.event());
        assertTrue(d.retryable());
        assertNoLearned(d);
    }

    @Test void qualificationRejectIsNotRetryableAndDoesNotClaimSuccess() {
        var d = LearnerChainDiagnostics.qualificationRejected("gate hard-fail");
        assertEquals("experience_qualification_rejected", d.event());
        assertEquals("REJECTED", d.outcome());
        assertFalse(d.retryable());
        assertNoLearned(d);
    }

    @Test void retrySuccessReportsStoredCountOnly() {
        var d = LearnerChainDiagnostics.retrySucceeded(1);
        assertEquals("experience_retry_succeeded", d.event());
        assertEquals("STORED", d.outcome());
        assertTrue(d.message().contains("stored 1 entry"), d.message());
    }
}
