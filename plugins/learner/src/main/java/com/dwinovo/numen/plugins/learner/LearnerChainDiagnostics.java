package com.dwinovo.numen.plugins.learner;

/** 2026-11-07 C0: failure diagnostics vocabulary for memo->review->store->publish chain.
 *  Failures/rejections must never claim an entry was learned/stored. */
final class LearnerChainDiagnostics {
    private LearnerChainDiagnostics() {}

    record Diag(String step, String outcome, String event, boolean retryable, String message) {}

    static Diag reviewFailed(String reason) {
        return new Diag("review", "FAILED", "auto_review_failed", true,
                "review failed: " + safe(reason) + "; no entry stored");
    }

    static Diag storeFailed(String reason) {
        return new Diag("store", "FAILED", "experience_store_failed", true,
                "store failed: " + safe(reason) + "; NOT stored, retry is idempotent");
    }

    static Diag qualificationRejected(String reason) {
        return new Diag("qualify", "REJECTED", "experience_qualification_rejected", false,
                "qualification rejected: " + safe(reason) + "; no entry stored");
    }

    static Diag retrySucceeded(int stored) {
        return new Diag("store", "STORED", "experience_retry_succeeded", false,
                "retry succeeded: stored " + Math.max(0, stored) + " entry");
    }

    private static String safe(String s) { return s == null || s.isBlank() ? "unknown" : s; }
}
