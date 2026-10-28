package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 重写"后面的路"（{@link TaskChain#replaceFuturePrimaries}）：2026-10-28 主人要的
 * 「他应该能自由选择改掉整条链路，或者只改这一点」。
 *
 * <p>边界是这条测试的重点：**当前一级及之前一字不动**。已完成的节点是事实，
 * 一次重规划不能把它们抹掉（75号 §15.3-4）。
 */
class TaskChainFuturePrimariesTest {

    private static TaskChain threeStageChain() {
        return new TaskChain(new Goal("g", "通关", List.of(
                new PrimaryGoal("p1", "木头时代", List.of(
                        Subtask.hardCoded("p1-0", "砍树",
                                Map.of("asset_key", "minecraft:oak_log", "minimum", 3)))),
                PrimaryGoal.unexpanded("p2", "石器时代", List.of(
                        new AssetRequirement("minecraft:stone_pickaxe", 1))),
                PrimaryGoal.unexpanded("p3", "铁器时代", List.of()))));
    }

    @Test
    void rewritesOnlyTheStagesAfterTheCurrentOne() {
        TaskChain c = threeStageChain();
        c.startCurrent();                       // 当前一级 = p1
        long before = c.stateVersion();

        int count = c.replaceFuturePrimaries(List.of(
                PrimaryGoal.unexpanded("p2b", "先回主世界补齐", List.of()),
                PrimaryGoal.unexpanded("p3b", "再下矿", List.of(
                        new AssetRequirement("minecraft:iron_pickaxe", 1)))));

        assertEquals(2, count);
        List<PrimaryGoal> all = c.goal().primaryGoals();
        assertEquals(List.of("p1", "p2b", "p3b"), all.stream().map(PrimaryGoal::id).toList());
        assertEquals("p1", c.currentPrimary().id(), "当前一级不许被换掉");
        assertEquals(1, c.currentPrimary().subtasks().size(), "当前一级的二级也不许动");
        assertEquals("RUNNING", c.currentSubtaskStatus().name(), "在跑的那一步不许被重规划打断");
        assertEquals(before + 1, c.stateVersion(), "换后面的路 = 一次安全提交");
    }

    @Test
    void emptyReplacementMeansDropEverythingAfterTheCurrentStage() {
        TaskChain c = threeStageChain();
        c.startCurrent();

        assertEquals(0, c.replaceFuturePrimaries(List.of()));
        assertEquals(1, c.goal().primaryGoals().size(), "只差最后一件东西时，允许把后面的路砍空");
        assertEquals("p1", c.currentPrimary().id());
    }

    @Test
    void expandedOrDuplicatedOrReusedIdsAreRefused() {
        TaskChain c = threeStageChain();
        c.startCurrent();

        assertThrows(IllegalArgumentException.class, () -> c.replaceFuturePrimaries(List.of(
                        new PrimaryGoal("pX", "带二级的", List.of(
                                Subtask.hardCoded("pX-0", "x",
                                        Map.of("asset_key", "minecraft:dirt", "minimum", 1)))))),
                "新一级必须未展开：二级归懒展开，不能在这里塞（否则 §9 懒加载纪律破洞）");

        assertThrows(IllegalArgumentException.class, () -> c.replaceFuturePrimaries(List.of(
                        PrimaryGoal.unexpanded("p2b", "a", List.of()),
                        PrimaryGoal.unexpanded("p2b", "b", List.of()))),
                "同一批里 id 撞号");

        assertThrows(IllegalArgumentException.class, () -> c.replaceFuturePrimaries(List.of(
                        PrimaryGoal.unexpanded("p2", "复用已被替换掉的 id", List.of()))),
                "id 全链唯一：复用旧 id 会让迟到的回执/日志对不上");

        assertThrows(IllegalArgumentException.class, () -> c.replaceFuturePrimaries(List.of(
                        PrimaryGoal.unexpanded("p1", "复用当前一级 id", List.of()))),
                "当前一级的 id 也在占用表里");
    }

    @Test
    void satisfiedCompletionFactsAreNotTouchedByARewrite() {
        TaskChain c = new TaskChain(new Goal("g", "通关", List.of(
                new PrimaryGoal("p1", "木头时代", List.of(
                        Subtask.hardCoded("p1-0", "砍树",
                                Map.of("asset_key", "minecraft:oak_log", "minimum", 3)),
                        Subtask.hardCoded("p1-1", "做工作台",
                                Map.of("asset_key", "minecraft:crafting_table", "minimum", 1)))),
                PrimaryGoal.unexpanded("p2", "石器时代", List.of()))));
        c.startCurrent();
        c.applyHardCodedResult("p1-0", true);
        c.startCurrent();                        // 推进到 p1-1 并开跑（PENDING → RUNNING）
        c.applyHardCodedResult("p1-1", true);    // 一级 p1 的二级全部完成（statuses 记 COMPLETED）

        TaskChain restored = TaskChain.fromJson(c.toJson());
        restored.replaceFuturePrimaries(List.of(PrimaryGoal.unexpanded("p2b", "新的第二段", List.of())));

        assertEquals(c.currentPrimary().id(), restored.currentPrimary().id(),
                "重写后面的路不许把'已经走完的那一级'改成别的");
        assertEquals("p2b", restored.goal().primaryGoals().get(1).id());
    }
}
