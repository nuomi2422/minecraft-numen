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
    void replay2032MineMismatchBecomesNewTrusted() {
        // 20:32:38 的真实案例：mine memo（journaled_by=server-async，可信）被检索判成
        // 与三条 goto 条目同族 → 旧逻辑 REVISE → 10% 阈值 → topicMismatch 丢弃。
        // 新逻辑：近重复阈值 0.5 → 无近重复 → 可信来源 → NEW_TRUSTED。
        String draft = "mine 的可达性前提是『从当前站立点出发有现成的可行走路径』：它把世界里存在多少块（found 64 iron_ore+1）和我实际能走到几块分开算，走不到就整条任务失败并返回 'could not reach any of them from x,y,z — no path out, and nothing minable in place'。当目标在身体所在的另一层（本例身体 y=63~70、矿 y=51）时，地表没有任何向下通道 ⇒ 永远不可达；而 goto 只对齐水平 x/z 并落到地面（y=70），不改变层高，所以原地重试 N 次结果逐字相同。";
        List<String> titles = List.of(
                "goto 的寻路以『净位移』判定进度",
                "goto 以净位移判定进度，绕行地形中会因方向反转中止",
                "goto 的寻路以『净位移』判定进度");
        List<String> pubs = List.of("DRAFT", "DRAFT", "DRAFT");
        assertEquals(-1, LearnerReviewTool.bestReviseIndex(titles, pubs, draft),
                "mine 与 goto 条目不是近重复（标题重叠 < 0.5）");
        assertEquals("NEW_TRUSTED", LearnerReviewTool.reviseOutcome(-1, true),
                "可信来源 + 无近重复 → 按草稿身份落 NEW");
        assertEquals("SKIP", LearnerReviewTool.reviseOutcome(-1, false),
                "同案若来源不可信仍跳过");
    }



    @Test
    void replay1939DeathLessonsWouldBeNewTrustedAfterTheFix() {
        // 19:39:59 三条真实死亡草稿：旧逻辑被 debug_kill 判同族 → topicMismatch 丢弃。
        // 新逻辑：标题重叠<0.5 → 无近重复；stage=death 属可信来源 → NEW_TRUSTED。
        assertDeathBecomesNewTrusted(
                "death44Lava",
                "下界岩浆的伤害是持续 onFire 结算：身体一接触岩浆就开始每半秒连续掉血，直到离开岩浆为止；而这个维度没有兜底——床会爆炸且不设重生点（bedDimension=overworld、bedDistance=-1），水桶放不出水，唯一的保险是抗火药或事先挖出的隔离走道。",
                "debug_kill 走原版死亡流程，代价是真丢东西");
        assertDeathBecomesNewTrusted(
                "death45Fall",
                "goto 在『可开路』模式下遇到大幅垂直差（本例 y 48→63）会把抬升拆成挖台阶/堆柱子，最后一步是从柱顶或台阶边缘落下；回执只报 'reached the HORIZONTAL position … but the target cell is y=63 — N block(s) …'——它承认没到目标层，却已经把人放在半空的落脚点上，之后任何一次移动都可能是自由落体。",
                "debug_kill 走原版死亡流程（entity.kill()，当刻结算），因此背包全部物品会立刻进入死亡资产记账并可能被判 confirmed_lost");
        assertDeathBecomesNewTrusted(
                "death46Zombie",
                "夜间野外是刷怪场：isNight=true 且所在格 blockLight=0 时僵尸会就地刷新并主动近身，而无武器无护甲的身体在硬直交换里必输；睡眠侧线不是可选项——它是把身体从『可被杀』切回『安全』的唯一低成本手段，且床就在 18 格内。",
                "debug_kill 走原版死亡流程（entity.kill()，当刻结算），因此背包全部物品会立刻进入死亡资产记账并可能被判 confirmed_lost");
    }

    private static void assertDeathBecomesNewTrusted(String label, String draft, String hitTitle) {
        int idx = LearnerReviewTool.bestReviseIndex(List.of(hitTitle), List.of("DRAFT"), draft);
        assertEquals(-1, idx, label + "：不应把死亡教训判成 debug_kill 的近重复");
        assertEquals("NEW_TRUSTED", LearnerReviewTool.reviseOutcome(idx, true),
                label + "：death 是可信来源 → 按草稿身份落 NEW");
    }

    @Test
    void outcomeDependsOnTrustWhenNoNearDuplicate() {
        assertEquals("REVISE", LearnerReviewTool.reviseOutcome(0, false));
        assertEquals("REVISE", LearnerReviewTool.reviseOutcome(2, true));
        assertEquals("NEW_TRUSTED", LearnerReviewTool.reviseOutcome(-1, true));
        assertEquals("SKIP", LearnerReviewTool.reviseOutcome(-1, false));
    }
}
