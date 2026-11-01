package com.dwinovo.numen.plugins.learner;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-02（B+F11 批次）：REVISE 目标选择与近重复判据。
 *
 * <p>审核窗口 05:32：0.10 不是近重复；I1 跳过停用目标必须留痕；NEW 只在可信来源时发生。
 */
class LearnerReviewMismatchPolicyTest {

    @Test
    void inactiveTargetsAreRecognized() {
        assertTrue(LearnerReviewTool.inactivePublication("SUSPENDED"));
        assertTrue(LearnerReviewTool.inactivePublication("revoked"));
        assertFalse(LearnerReviewTool.inactivePublication("DRAFT"));
        assertFalse(LearnerReviewTool.inactivePublication("PUBLISHED"));
        assertFalse(LearnerReviewTool.inactivePublication(""));
        assertFalse(LearnerReviewTool.inactivePublication(null));
    }

    @Test
    void nearDuplicateRequiresAtLeastHalfTitleOverlap() {
        String draft = "夜间没有甲被僵尸杀死，需要先补甲或者睡床";
        // 完全相同 → 1.0 ≥ 0.5 → 命中
        assertEquals(0, LearnerReviewTool.bestReviseIndex(
                List.of(draft), List.of("DRAFT"), draft));
        // 完全不相干 → < 0.5 → 没有近重复
        assertEquals(-1, LearnerReviewTool.bestReviseIndex(
                List.of("合成木镐需要工作台"), List.of("DRAFT"), draft));
    }

    @Test
    void inactiveTargetIsSkippedEvenWhenTitleMatches() {
        String draft = "夜间没有甲被僵尸杀死";
        assertEquals(-1, LearnerReviewTool.bestReviseIndex(
                List.of(draft), List.of("SUSPENDED"), draft),
                "停用目标不能当 REVISE 落点");
        assertEquals(-1, LearnerReviewTool.bestReviseIndex(
                List.of(draft), List.of("REVOKED"), draft));
    }

    @Test
    void picksTheClosestActiveTargetAndIgnoresInactiveOnes() {
        String draft = "下界岩浆会持续燃烧并把人烧死";
        String closer = "下界岩浆会持续燃烧把人烧死";
        List<String> titles = List.of(closer, "完全无关的合成配方");
        List<String> pubs = List.of("DRAFT", "DRAFT");
        assertEquals(0, LearnerReviewTool.bestReviseIndex(titles, pubs, draft));
        // 把更接近的那条标成 SUSPENDED → 退回到不相干的那条 → 没有近重复
        assertEquals(-1, LearnerReviewTool.bestReviseIndex(
                titles, List.of("SUSPENDED", "DRAFT"), draft));
    }

    @Test
    void replay0037SuspendedGotoEntryIsNeverRewritten() {
        // 00:37 那次事故：REVISE 原地改写了这条 SUSPENDED 条目。F11 I1 后它必须被拒绝。
        String suspended = "goto 的寻路以『净位移』判定进度：当目标在需要绕远的地形中（洞穴、坑洼、树林、单格宽通道）时，"
                + "寻路会在两个等效绕行方向间反复切换，累计走了约 10 格而净位移仍为 0，超阈值即中止并返回 route unusable";
        String draft = suspended;   // 最坏情况：草稿标题与停用条目完全一致
        assertEquals(-1, LearnerReviewTool.bestReviseIndex(
                List.of(suspended), List.of("SUSPENDED"), draft),
                "SUSPENDED 条目不得再被 REVISE 改写");
        assertEquals("SKIP", LearnerReviewTool.reviseOutcome(-1, false),
                "来源不可信时跳过（不回开假失败的门）");
    }

    @Test
    void outcomeDependsOnTrustWhenNoNearDuplicate() {
        assertEquals("REVISE", LearnerReviewTool.reviseOutcome(0, false));
        assertEquals("REVISE", LearnerReviewTool.reviseOutcome(2, true));
        assertEquals("NEW_TRUSTED", LearnerReviewTool.reviseOutcome(-1, true));
        assertEquals("SKIP", LearnerReviewTool.reviseOutcome(-1, false));
    }
}
