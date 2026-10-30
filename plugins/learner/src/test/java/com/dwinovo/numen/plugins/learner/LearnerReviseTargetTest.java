package com.dwinovo.numen.plugins.learner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-31（V-20261031-15）：REVISE 目标 type 解析。 */
class LearnerReviseTargetTest {

    @Test
    void typeFromTargetId() {
        assertEquals("execution", LearnerReviewTool.targetTypeOf("execution|goto 的寻路…"));
        assertEquals("tool_defect", LearnerReviewTool.targetTypeOf("tool_defect|x"));
    }

    @Test
    void missingBarMeansUnknown() {
        assertEquals("", LearnerReviewTool.targetTypeOf("no-bar"));
        assertEquals("", LearnerReviewTool.targetTypeOf(null));
    }
}
