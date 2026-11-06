package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.Experience;
import com.dwinovo.numen.plugins.learner.core.Verdict;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-06: WRITE_EXPERIENCE 要用枚举比较；空 evidence 必须触发补证据重试。 */
class HasBlankEvidenceTest {

    private static Verdict verdict(Verdict.Action action, Experience exp) {
        return new Verdict("m-1", List.of(action), 0.5, "r", exp, "", "", "", List.of(), List.of());
    }

    private static Experience exp(String evidence) {
        return new Experience("m", "p", "f", "o", "d", "e", evidence, "FAILURE");
    }

    @Test void writeExperienceWithNullExperienceNeedsRetry() {
        assertTrue(LearnerReviewer.hasBlankEvidence(
                LearnerReviewer.ReviewOutcome.ok(List.of(verdict(Verdict.Action.WRITE_EXPERIENCE, null)))));
    }

    @Test void writeExperienceWithBlankEvidenceNeedsRetry() {
        assertTrue(LearnerReviewer.hasBlankEvidence(
                LearnerReviewer.ReviewOutcome.ok(List.of(verdict(Verdict.Action.WRITE_EXPERIENCE, exp(" "))))));
    }

    @Test void writeExperienceWithEvidenceDoesNotNeedRetry() {
        assertFalse(LearnerReviewer.hasBlankEvidence(
                LearnerReviewer.ReviewOutcome.ok(List.of(verdict(Verdict.Action.WRITE_EXPERIENCE, exp("event:x"))))));
    }

    @Test void nonWriteActionDoesNotNeedRetry() {
        assertFalse(LearnerReviewer.hasBlankEvidence(
                LearnerReviewer.ReviewOutcome.ok(List.of(verdict(Verdict.Action.NO_ACTION, null)))));
    }

    @Test void failedOutcomeDoesNotNeedRetry() {
        assertFalse(LearnerReviewer.hasBlankEvidence(LearnerReviewer.ReviewOutcome.fail("x")));
        assertFalse(LearnerReviewer.hasBlankEvidence(null));
    }
}
