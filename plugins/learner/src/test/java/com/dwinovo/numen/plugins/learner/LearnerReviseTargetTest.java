package com.dwinovo.numen.plugins.learner;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-10-31（V-20261031-15/16）：REVISE 目标 type/title 解析。 */
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

    @Test
    void titleFromTargetIdNotTruncated() {
        String longTitle = "goto 的寻路以『净位移』判定进度：当目标在需要绕远的地形中（洞穴、坑洼、树林、单格宽通道）时，"
                + "寻路会在两个等效绕行方向间反复切换，累计走了约 10 格而净位移仍为 0，超阈值即中止并返回 "
                + "route unusable: no net progress, direction keeps reversing。";
        String id = "execution|" + longTitle;
        assertEquals(longTitle, LearnerReviewTool.targetTitleOf(id, "显示截断的短标题"));
        assertEquals("显示截断的短标题", LearnerReviewTool.targetTitleOf("no-bar", "显示截断的短标题"));
        assertEquals("", LearnerReviewTool.targetTitleOf(null, null));
    }
}
