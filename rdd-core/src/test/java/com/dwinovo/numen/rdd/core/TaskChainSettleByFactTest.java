package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.PrimaryGoalStatus;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.SubtaskStatus;
import com.dwinovo.numen.rdd.api.SupervisorDecision;
import com.dwinovo.numen.rdd.api.SupervisorDecisionType;
import com.dwinovo.numen.rdd.fact.StageKeyNormalizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 事实达成 / 提前收口（{@link TaskChain#settlePrimaryByFact}）：2026-10-28 主人的口径 ——
 * 「提前收口是规划者决定的……我拿到了钻石镐，挖铁镐就没有意义了……但我也可能用钻石镐去挖铁」。
 *
 * <p>所以这里钉的不是"哪件物品能跳过哪一级"（代码不猜），而是四条结构纪律：
 * <ol>
 *   <li>收口必须带理由（否则事后无从复核）；</li>
 *   <li>当前级收口 = 立即推进，不走 {@code AWAITING_SUPERVISOR}（这不是"跑完了"，是"不用跑了"）；</li>
 *   <li>当前级里<b>真做完</b>的二级保持 COMPLETED（事实不降级），其余转 SKIPPED 并留理由；</li>
 *   <li>后面的级只加事实键，指针不动；走到那里时自动跳过；指针之前不许改（改历史）。</li>
 * </ol>
 */
class TaskChainSettleByFactTest {

    private static TaskChain chain() {
        return new TaskChain(new Goal("g", "从木镐到钻石镐", List.of(
                new PrimaryGoal("p1", "木器时代", List.of(
                        Subtask.hardCoded("p1-0", "砍三块木头",
                                Map.of("asset_key", "minecraft:oak_log", "minimum", 3)))),
                new PrimaryGoal("p2", "石器时代", List.of(
                        Subtask.hardCoded("p2-0", "做石镐",
                                Map.of("asset_key", "minecraft:stone_pickaxe", "minimum", 1)))),
                new PrimaryGoal("p3", "铁器时代", List.of(
                        Subtask.hardCoded("p3-0", "做铁镐",
                                Map.of("asset_key", "minecraft:iron_pickaxe", "minimum", 1)))),
                new PrimaryGoal("p4", "钻石时代", List.of(
                        Subtask.hardCoded("p4-0", "做钻石镐",
                                Map.of("asset_key", "minecraft:diamond_pickaxe", "minimum", 1)))))));
    }

    @Test
    void settlingTheCurrentStageAdvancesImmediatelyAndLeavesAReason() {
        TaskChain c = chain();
        c.startCurrent();
        long before = c.stateVersion();

        String desc = c.settlePrimaryByFact("p1", "我已经有钻石镐了，砍树这一步没有意义");

        assertEquals("木器时代", desc);
        assertEquals("p2", c.currentPrimary().id(), "收口当前级 = 立刻推进（不占用一次 supervise 确认）");
        assertEquals(PrimaryGoalStatus.PENDING, c.primaryStatus());
        assertEquals(before + 1, c.stateVersion(), "计划事实变了 = 一次安全提交");
        assertEquals(SubtaskStatus.SKIPPED, c.subtaskStatuses().get("p1-0"), "未完成的二级收成 SKIPPED，不留悬空 RUNNING");
        assertTrue(c.skipReason("p1-0").startsWith("stage settled by fact"),
                "跳过理由要能追到'为什么'：" + c.skipReason("p1-0"));
        assertTrue(c.satisfiedStages().contains(StageKeyNormalizer.normalize("木器时代")),
                "记的是阶段键（可继承），不是节点跳过");
    }

    @Test
    void settlingAFutureStageOnlyRecordsTheFactAndLeavesTheCursorAlone() {
        TaskChain c = chain();
        c.startCurrent();

        assertEquals("铁器时代", c.settlePrimaryByFact("p3", "有钻石镐了，铁镐这一级没必要单独走"));
        assertEquals("p1", c.currentPrimary().id(), "后面的级被收口，不许挪动当前指针");
        assertEquals(1, c.settledStages().size(), "settledStages 只记被收口的（这里应只有 p3）");
        assertTrue(c.settledStages().containsKey("p3"));
    }

    @Test
    void aSettledFutureStageIsSkippedWhenTheCursorReachesIt() {
        TaskChain c = chain();
        c.startCurrent();
        c.settlePrimaryByFact("p2", "石镐这一步对我没有意义");

        c.applyHardCodedResult("p1-0", true);          // 当前级跑完 → AWAITING_SUPERVISOR
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, c.primaryStatus());
        c.applySupervisorDecision(new SupervisorDecision(
                SupervisorDecisionType.CONFIRM, "p1", "木器时代达成"));

        assertEquals("p3", c.currentPrimary().id(), "被事实收口的 p2 必须被跳过（不是白跑一遍再跳过）");
    }

    @Test
    void settlingIsRefusedWithoutReasonUnknownIdOrBehindTheCursor() {
        TaskChain c = chain();
        c.startCurrent();

        assertThrows(IllegalArgumentException.class, () -> c.settlePrimaryByFact("p1", " "),
                "没有理由的收口不可复核，必须拒绝");
        assertThrows(IllegalArgumentException.class, () -> c.settlePrimaryByFact("p9", "不存在的一级"),
                "未知 id 不许静默成功");

        c.settlePrimaryByFact("p1", "已达成");
        assertThrows(IllegalStateException.class, () -> c.settlePrimaryByFact("p1", "回头再收一次"),
                "指针之前的级 = 历史，不许改");
    }

    @Test
    void genuinelyCompletedSubtasksAreNotDowngradedToSkipped() {
        TaskChain c = chain();
        c.startCurrent();
        c.applyHardCodedResult("p1-0", true);          // 真做完了

        c.settlePrimaryByFact("p1", "整级已经不需要了");

        assertEquals(SubtaskStatus.COMPLETED, c.subtaskStatuses().get("p1-0"),
                "做完的事实不许降级成 SKIPPED（否则完成率/台账全部失真）");
    }

    @Test
    void factSurvivesRestartThroughSatisfiedStages() {
        TaskChain c = chain();
        c.startCurrent();
        c.settlePrimaryByFact("p1", "已达成");

        TaskChain restored = TaskChain.fromJson(c.toJson());
        assertEquals("p2", restored.currentPrimary().id(),
                "收口事实随 satisfiedStages 持久化：重启后不许把被收口的级又排一遍");
    }
}
