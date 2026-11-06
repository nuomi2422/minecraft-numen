package com.dwinovo.numen.plugins.learner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-06: 第二跳准入与上游 effectiveDepth 的组合必须真实接线，且 nudge 不谎报条数。 */
class AutoReviewSecondHopTest {

    @Test void singleDeathAdmitsEvenWhenRealDepthIsOne() {
        int gateDepth = LearnerAutoIntake.effectiveDepth(1, true);
        assertTrue(LearnerAutoIntake.secondHopAdmits(1, gateDepth));
    }

    @Test void singleStaleMemoAdmitsAtStaleBoundary() {
        int gateDepth = LearnerAutoIntake.effectiveDepth(1,
                LearnerAutoIntake.staleEnough(LearnerAutoIntake.REVIEW_STALE_MS));
        assertTrue(LearnerAutoIntake.secondHopAdmits(1, gateDepth));
    }

    @Test void freshSingleMemoOneMsBeforeStaleBoundaryDoesNotAdmit() {
        int gateDepth = LearnerAutoIntake.effectiveDepth(1,
                LearnerAutoIntake.staleEnough(LearnerAutoIntake.REVIEW_STALE_MS - 1));
        assertFalse(LearnerAutoIntake.secondHopAdmits(1, gateDepth));
    }

    @Test void negativeRealDepthNeverAdmits() {
        assertFalse(LearnerAutoIntake.secondHopAdmits(-1, LearnerAutoIntake.effectiveDepth(-1, true)));
        assertFalse(LearnerAutoIntake.secondHopAdmits(0, LearnerAutoIntake.effectiveDepth(0, true)));
    }

    @Test void nudgeTextReportsRealDepthNotGateDepth() {
        String text = LearnerAutoIntake.nudgeText(1);
        assertTrue(text.contains("1 条"));
        assertFalse(text.contains("3 条"));
        assertTrue(text.contains("learner_review"));
    }

    @Test void cooldownBlocksSecondHop() {
        long now = 1_000_000_000L;
        assertFalse(LearnerAutoIntake.autoReviewDue(now - 20 * 60_000L, 0L, now - 5 * 60_000L, now, 3, false));
    }

    @Test void runningReviewBlocksSecondHop() {
        long now = 1_000_000_000L;
        assertFalse(LearnerAutoIntake.autoReviewDue(now - 20 * 60_000L, 0L, null, now, 3, true));
    }
}
