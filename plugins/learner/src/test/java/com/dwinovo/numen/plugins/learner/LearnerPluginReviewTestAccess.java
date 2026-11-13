package com.dwinovo.numen.plugins.learner;

import java.util.UUID;

/**
 * 2026-11-13: lets cross-package intake tests read the existing review state.
 * The original no-review assertion could not compile against package-private access;
 * keep production visibility and the assertion unchanged through this test-only bridge.
 */
public final class LearnerPluginReviewTestAccess {
    private LearnerPluginReviewTestAccess() {}

    public static Object runningReview(UUID companionId) {
        return LearnerPlugin.runningReview(companionId);
    }
}
