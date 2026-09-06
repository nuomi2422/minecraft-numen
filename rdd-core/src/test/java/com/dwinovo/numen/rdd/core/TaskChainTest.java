package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TaskChainTest {
    @Test void hardCodedSubtaskAdvancesWithoutSupervisor() {
        var primary = new PrimaryGoal("p", "prepare", java.util.List.of(
                Subtask.hardCoded("s1", "get stone", Map.of("item", "stone")),
                Subtask.hardCoded("s2", "get wood", Map.of("item", "wood"))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s1", true));
        assertEquals("s2", chain.currentSubtask().id());
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s2", true));
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, chain.primaryStatus());
        assertThrows(IllegalStateException.class, () -> chain.applyHardCodedResult("s2", true));
    }

    @Test void multiSubtaskStaysActiveUntilLastCompletes() {
        var primary = new PrimaryGoal("p", "prepare", java.util.List.of(
                Subtask.hardCoded("s1", "get stone", Map.of("asset_key", "minecraft:stone", "minimum", 1)),
                Subtask.hardCoded("s2", "get wood", Map.of("asset_key", "minecraft:oak_log", "minimum", 1))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s1", true));
        assertEquals("s2", chain.currentSubtask().id());
        assertEquals(PrimaryGoalStatus.ACTIVE, chain.primaryStatus()); // 未到最后一级仍 ACTIVE
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s2", true));
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, chain.primaryStatus());
    }

    @Test void stalledSubtaskCanResumeAndFail() {
        var primary = new PrimaryGoal("p", "mine", java.util.List.of(
                Subtask.hardCoded("s1", "mine diamond", Map.of("item", "diamond"))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        // RUNNING → STALLED（监督检测卡死：资产指纹无变化）
        chain.markStalled("s1", "asset fingerprint unchanged");
        assertEquals(SubtaskStatus.STALLED, chain.currentSubtaskStatus());
        // STALLED → RUNNING（拍醒后行为恢复）
        chain.resumeFromStalled("s1");
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
        // STALLED → FAILED（多次拍醒无效，Level 1 恢复兜底）
        chain.markStalled("s1", "stalled again");
        chain.markFailed("s1", "stalled after nudges");
        assertEquals(SubtaskStatus.FAILED, chain.currentSubtaskStatus());
    }

    @Test void persistsAndRestoresStateAcrossInstances() {
        var primary = new PrimaryGoal("p", "mine", java.util.List.of(
                Subtask.hardCoded("s1", "mine diamond", Map.of("item", "diamond")),
                Subtask.hardCoded("s2", "mine iron", Map.of("item", "iron"))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        chain.markStalled("s1", "stalled");
        // 导出 → 新实例恢复（模拟游戏重启）
        var restored = TaskChain.fromJson(chain.toJson());
        assertEquals(SubtaskStatus.STALLED, restored.currentSubtaskStatus());
        assertEquals("s1", restored.currentSubtask().id());
        assertEquals(PrimaryGoalStatus.ACTIVE, restored.primaryStatus());
        // 恢复后可继续走状态机（STALLED → RUNNING）
        restored.resumeFromStalled("s1");
        assertEquals(SubtaskStatus.RUNNING, restored.currentSubtaskStatus());
    }

    @Test void failedSubtaskCanRetry() {
        var primary = new PrimaryGoal("p", "mine", java.util.List.of(
                Subtask.hardCoded("s1", "mine diamond", Map.of("item", "diamond"))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        chain.markFailed("s1", "body task ended");
        assertEquals(SubtaskStatus.FAILED, chain.currentSubtaskStatus());
        // Level 2 局部恢复：失败二级重置 PENDING → 可重新 startCurrent（AI 换策略再试）
        chain.retrySubtask("s1");
        assertEquals(SubtaskStatus.PENDING, chain.currentSubtaskStatus());
        chain.startCurrent();
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
    }

    @Test void supervisorMustMatchAwaitingPrimary() {
        var primary = new PrimaryGoal("p", "prepare", java.util.List.of(
                Subtask.hardCoded("s", "get stone", Map.of("item", "stone"))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        chain.applyHardCodedResult("s", true);
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p", "observed"));
        assertEquals(PrimaryGoalStatus.COMPLETED, chain.primaryStatus());
    }

    @Test void snapshotContainsWholeChainAndCurrentPointer() {
        var primary = new PrimaryGoal("p", "prepare", java.util.List.of(
                Subtask.hardCoded("s1", "get stone", Map.of("asset_key", "minecraft:stone", "minimum", 1)),
                Subtask.hardCoded("s2", "get wood", Map.of("asset_key", "minecraft:oak_log", "minimum", 1))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        var snapshot = chain.snapshot();
        assertEquals("g", snapshot.get("goalId"));
        assertEquals("s1", snapshot.get("currentSubtaskId"));
        var primaries = (java.util.List<?>) snapshot.get("primaries");
        var primaryView = (java.util.Map<?, ?>) primaries.get(0);
        var subtasks = (java.util.List<?>) primaryView.get("subtasks");
        assertEquals(2, subtasks.size());
        assertEquals("RUNNING", ((java.util.Map<?, ?>) subtasks.get(0)).get("status"));
        assertEquals("PENDING", ((java.util.Map<?, ?>) subtasks.get(1)).get("status"));
    }

    @Test void dependencyGatedPrimaryWaitsUntilAssetsPresent() {
        var p1 = new PrimaryGoal("p1", "forge pickaxe", java.util.List.of(
                Subtask.hardCoded("s1", "make diamond pickaxe", Map.of("asset_key", "minecraft:diamond_pickaxe", "minimum", 1))));
        // p2 声明前置：必须有钻石镐 1 个才开工
        var p2 = new PrimaryGoal("p2", "mine obsidian", java.util.List.of(
                Subtask.hardCoded("s2", "hold obsidian", Map.of("asset_key", "minecraft:obsidian", "minimum", 1))),
                java.util.List.of(new AssetRequirement("minecraft:diamond_pickaxe", 1)));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(p1, p2)));
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s1", true)); // p1 完成 → AWAITING_SUPERVISOR
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p1", "done"));
        assertEquals("p2", chain.currentPrimary().id());
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
        // 前置钻石镐不在背包 → 拒绝激活，保持 WAITING，不派给 AI
        assertFalse(chain.activateCurrent(Map.of("minecraft:obsidian", 0)));
        assertEquals(PrimaryGoalStatus.WAITING, chain.primaryStatus());
        assertEquals(java.util.List.of("minecraft:diamond_pickaxe"), chain.snapshot().get("waitingFor"));
        // 前置到位 → 放行激活，首个二级 RUNNING
        assertTrue(chain.activateCurrent(Map.of("minecraft:obsidian", 0, "minecraft:diamond_pickaxe", 1)));
        assertEquals(PrimaryGoalStatus.ACTIVE, chain.primaryStatus());
        assertEquals("s2", chain.currentSubtask().id());
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
    }

    @Test void persistenceKeepsPrimaryDependency() {
        var p1 = new PrimaryGoal("p1", "a", java.util.List.of(
                Subtask.hardCoded("s1", "x", Map.of("asset_key", "minecraft:oak_log", "minimum", 1))));
        var p2 = new PrimaryGoal("p2", "b", java.util.List.of(
                Subtask.hardCoded("s2", "y", Map.of("asset_key", "minecraft:obsidian", "minimum", 1))),
                java.util.List.of(new AssetRequirement("minecraft:diamond_pickaxe", 3)));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(p1, p2)));
        var restored = TaskChain.fromJson(chain.toJson());
        assertEquals(2, restored.goal().primaryGoals().size());
        var rp2 = restored.goal().primaryGoals().get(1);
        assertEquals(1, rp2.waitFor().size());
        assertEquals("minecraft:diamond_pickaxe", rp2.waitFor().get(0).assetKey());
        assertEquals(3, rp2.waitFor().get(0).minimum());
    }
}
