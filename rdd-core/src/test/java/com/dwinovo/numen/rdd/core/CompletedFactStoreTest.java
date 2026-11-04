package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.SubtaskStatus;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import com.dwinovo.numen.rdd.fact.GoalLineage;
import com.dwinovo.numen.rdd.fact.StageKeyNormalizer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0 完成事实继承（CompletedFactStore + TaskChain.satisfiedStages）验收测试。
 *
 * <p>对应组长定稿的 Test1-5，外加归一/血缘近似的边界。规则：只继承"阶段级"事实、
 * 不伪造二级完成、不同战役不污染、跨重启/跨重绑保留。
 */
class CompletedFactStoreTest {

    private static Goal stagedGoal(String goalId, String objective, String... stageDescriptions) {
        List<PrimaryGoal> primaries = new java.util.ArrayList<>();
        for (int i = 0; i < stageDescriptions.length; i++) {
            primaries.add(PrimaryGoal.unexpanded("primary-" + i, stageDescriptions[i], List.of()));
        }
        return new Goal(goalId, objective, primaries);
    }

    // ===== Test1：阶段事实可继承，currentPrimary() 跳过已达成一级 =====
    @Test
    void completedStageIsInheritedAndCurrentPrimarySkipsIt() {
        Goal goal = stagedGoal("goal-abc12345", "击败末影龙", "获得钻石装备", "进入下界", "击杀末影龙");
        CompletedFactStore store = new CompletedFactStore();
        store.recordStage(goal, "获得钻石装备", 1000L, "primary confirmed");

        TaskChain chain = new TaskChain(goal, store.satisfiedStageKeys(goal));

        assertTrue(chain.satisfiedStages().contains(StageKeyNormalizer.normalize("获得钻石装备")));
        assertEquals("进入下界", chain.currentPrimary().description()); // 跳过已达成的一级
        assertEquals(com.dwinovo.numen.rdd.api.PrimaryGoalStatus.PENDING, chain.primaryStatus());
    }

    // ===== Test2：不为已达成一级伪造二级完成（懒展开一级本无二级） =====
    @Test
    void satisfiedStageIsNotFabricatedIntoStatuses() {
        Goal goal = stagedGoal("goal-abc12345", "击败末影龙", "获得钻石装备", "击杀末影龙");
        CompletedFactStore store = new CompletedFactStore();
        store.recordStage(goal, "获得钻石装备", 1000L, "primary confirmed");

        TaskChain chain = new TaskChain(goal, store.satisfiedStageKeys(goal));
        // 未展开一级没有二级 → statuses 里不应出现任何 COMPLETED 记录
        assertFalse(chain.subtaskStatuses().containsValue(SubtaskStatus.COMPLETED));
        assertTrue(chain.subtaskStatuses().isEmpty());
    }

    // ===== Test3：不同目标（不同血缘）不得污染 =====
    @Test
    void differentGoalLineageDoesNotInherit() {
        Goal dragon = stagedGoal("goal-abc12345", "击败末影龙", "获得钻石装备", "击杀末影龙");
        Goal build = stagedGoal("goal-abc12345", "建造基地", "获得钻石装备", "扩建基地");
        CompletedFactStore store = new CompletedFactStore();
        store.recordStage(dragon, "获得钻石装备", 1000L, "primary confirmed");

        // 同 goalId 但目标正文不同 → 血缘不同、指纹不相似 → 不继承
        assertTrue(store.satisfiedStageKeys(build).isEmpty());
        TaskChain chain = new TaskChain(build, store.satisfiedStageKeys(build));
        assertEquals("获得钻石装备", chain.currentPrimary().description());
        assertTrue(chain.satisfiedStages().isEmpty());
    }

    // ===== Test4：跨重启（JSON 往返）保留 satisfiedStages =====
    @Test
    void satisfiedStagesSurviveJsonRoundTrip() {
        Goal goal = stagedGoal("goal-abc12345", "击败末影龙", "获得钻石装备", "击杀末影龙");
        CompletedFactStore store = new CompletedFactStore();
        store.recordStage(goal, "获得钻石装备", 1000L, "primary confirmed");

        TaskChain chain = new TaskChain(goal, store.satisfiedStageKeys(goal));
        TaskChain restored = TaskChain.fromJson(chain.toJson());

        assertEquals(chain.satisfiedStages(), restored.satisfiedStages());
        assertEquals("击杀末影龙", restored.currentPrimary().description());

        // 事实仓库本身也需往返保真
        CompletedFactStore reloaded = CompletedFactStore.fromJson(store.toJson());
        assertEquals(store.satisfiedStageKeys(goal), reloaded.satisfiedStageKeys(goal));
    }

    // ===== Test5：全阶段达成 → 链直接 COMPLETED =====
    @Test
    void allStagesSatisfiedMakesChainCompleted() {
        Goal goal = stagedGoal("goal-abc12345", "击败末影龙", "获得钻石装备", "击杀末影龙");
        CompletedFactStore store = new CompletedFactStore();
        store.recordStage(goal, "获得钻石装备", 1000L, "confirmed");
        store.recordStage(goal, "击杀末影龙", 2000L, "confirmed");

        TaskChain chain = new TaskChain(goal, store.satisfiedStageKeys(goal));
        assertEquals(com.dwinovo.numen.rdd.api.PrimaryGoalStatus.COMPLETED, chain.primaryStatus());
        assertEquals(2, chain.satisfiedStages().size());
    }

    // ===== CONFIRM 推进时跳过"被事实命中"的中间一级 =====
    @Test
    void confirmAdvancesPastSatisfiedStages() {
        Goal goal = stagedGoal("goal-abc12345", "击败末影龙", "阶段A", "阶段B", "阶段C");
        // 阶段B 有历史完成事实，但阶段A没有 → 链从A开始；A确认后应直接跳到C，跳过B
        CompletedFactStore store = new CompletedFactStore();
        store.recordStage(goal, "阶段B", 1000L, "confirmed");
        TaskChain chain = new TaskChain(goal, store.satisfiedStageKeys(goal));
        assertEquals("阶段A", chain.currentPrimary().description());

        // 让阶段A可确认：展开二级 → startCurrent(PENDING→ACTIVE→RUNNING) → 完成 → AWAITING_SUPERVISOR
        chain.expandCurrentPrimary(List.of(Subtask.hardCoded("s-a", "做A", Map.of("asset_key", "goal"), null)));
        chain.startCurrent();
        chain.applyHardCodedResult("s-a", true);
        assertEquals(com.dwinovo.numen.rdd.api.PrimaryGoalStatus.AWAITING_SUPERVISOR, chain.primaryStatus());

        chain.applySupervisorDecision(new com.dwinovo.numen.rdd.api.SupervisorDecision(
                com.dwinovo.numen.rdd.api.SupervisorDecisionType.CONFIRM, "primary-0", "done"));
        assertEquals("阶段C", chain.currentPrimary().description()); // 跳过已完成的阶段B
    }

    // ===== 归一：动作词/标点/空白差异仍认同一阶段；但不做同义词 =====
    @Test
    void normalizerRecognizesWordingVariantsButNotSynonyms() {
        assertEquals(StageKeyNormalizer.normalize("获得钻石装备"),
                StageKeyNormalizer.normalize("获取 钻石装备！"));
        assertNotEquals(StageKeyNormalizer.normalize("钻石装备"),
                StageKeyNormalizer.normalize("钻石套")); // P0 不做同义词
    }

    // ===== Level-2：同 goalId 且目标指纹近似 → 只继承阶段事实 =====
    @Test
    void similarObjectiveInheritsWhenFingerprintClose() {
        Goal a = stagedGoal("goal-abc12345", "击败末影龙", "获得钻石装备", "击杀末影龙");
        Goal b = stagedGoal("goal-abc12345", "击败末影龙任务", "获得钻石装备", "击杀末影龙");
        assertTrue(GoalLineage.similar(GoalLineage.fingerprint(a.description()), GoalLineage.fingerprint(b.description())));
        CompletedFactStore store = new CompletedFactStore();
        store.recordStage(a, "获得钻石装备", 1000L, "confirmed");

        assertTrue(store.satisfiedStageKeys(b).contains(StageKeyNormalizer.normalize("获得钻石装备")));
    }

    // ===== 旧 JSON 无 satisfiedStages 字段 → 空集（向后兼容） =====
    @Test
    void legacyJsonWithoutSatisfiedStagesLoadsEmpty() {
        Goal goal = stagedGoal("goal-abc12345", "击败末影龙", "阶段A");
        TaskChain legacy = new TaskChain(goal);
        String json = legacy.toJson().replaceAll(",\"satisfiedStages\":\\[[^\\]]*\\]", "");
        TaskChain restored = TaskChain.fromJson(json);
        assertTrue(restored.satisfiedStages().isEmpty());
    }

    // ===== GPT-A：已满足一级被跳过，展开作用在下一个（A 不被碰） =====
    @Test
    void satisfiedStageIsSkippedNotExpanded() {
        Goal goal = stagedGoal("goal-abc12345", "击败末影龙", "阶段A", "阶段B");
        CompletedFactStore store = new CompletedFactStore();
        store.recordStage(goal, "阶段A", 1L, "confirmed");
        TaskChain chain = new TaskChain(goal, store.satisfiedStageKeys(goal));
        assertEquals("阶段B", chain.currentPrimary().description());
        chain.expandCurrentPrimary(List.of(Subtask.hardCoded("s-b", "做B", Map.of("asset_key", "goal"), null)));
        assertEquals("阶段B", chain.currentPrimary().description());
        assertTrue(chain.satisfiedStages().contains(StageKeyNormalizer.normalize("阶段A")));
    }

    // ===== GPT-B：已满足一级不参与 WAITING（事实优先，直接看下一个） =====
    @Test
    void satisfiedStageDoesNotWaitOnAssets() {
        java.util.UUID uuid = java.util.UUID.fromString("00000000-0000-0000-0000-0000000000ab");
        Goal goal = RddChainFactory.fromStages(uuid, "击败末影龙", List.of(
                new PrimarySpec("阶段A", List.of()),
                new PrimarySpec("阶段B", List.of(new AssetRequirement("wood")))));
        CompletedFactStore store = new CompletedFactStore();
        store.recordStage(goal, "阶段A", 1L, "confirmed");
        TaskChain chain = new TaskChain(goal, store.satisfiedStageKeys(goal));
        chain.expandCurrentPrimary(List.of(Subtask.hardCoded("s-b", "做B", Map.of("asset_key", "wood"), null)));
        assertFalse(chain.activateCurrent(Map.of()));
        assertEquals(com.dwinovo.numen.rdd.api.PrimaryGoalStatus.WAITING, chain.primaryStatus());
        assertEquals("阶段B", chain.currentPrimary().description());
    }

    // ===== 跨同伴同目标不互相命中（goalId 不同 → 血缘不同） =====
    @Test
    void differentCompanionDoesNotInherit() {
        Goal c1 = stagedGoal("goal-aaaa1111", "击败末影龙", "击败末影龙阶段");
        Goal c2 = stagedGoal("goal-bbbb2222", "击败末影龙", "击败末影龙阶段");
        CompletedFactStore store = new CompletedFactStore();
        store.recordStage(c1, "击败末影龙阶段", 1L, "confirmed");
        assertTrue(store.satisfiedStageKeys(c2).isEmpty());
    }

    // ===== DS 反例：相似目标但阶段不同，不得误跳 =====
    @Test
    void similarObjectiveMustNotWronglySkipDifferentStage() {
        Goal wood = stagedGoal("goal-abc12345", "采集10个木头", "采集木头");
        Goal stone = stagedGoal("goal-abc12345", "采集10个石头", "采集石头");
        CompletedFactStore store = new CompletedFactStore();
        store.recordStage(wood, "采集木头", 1L, "confirmed");
        assertFalse(store.satisfiedStageKeys(stone).contains(StageKeyNormalizer.normalize("采集石头")));
        TaskChain chain = new TaskChain(stone, store.satisfiedStageKeys(stone));
        assertEquals("采集石头", chain.currentPrimary().description());
    }

    // ===== 全停用词/纯标点：归一为空 → 原文哈希，不同输入不同键 =====
    @Test
    void allStopwordDescriptionsFallBackToDistinctHashes() {
        assertNotEquals(StageKeyNormalizer.normalize("完成"), StageKeyNormalizer.normalize("进行"));
        assertTrue(StageKeyNormalizer.normalize("！！！").startsWith("raw:"));
    }

    // ===== 未知阶段键被忽略（不崩、不误跳） =====
    @Test
    void unknownSatisfiedKeyIsIgnored() {
        Goal goal = stagedGoal("goal-abc12345", "击败末影龙", "阶段A");
        TaskChain chain = new TaskChain(goal, java.util.Set.of("不存在的阶段键"));
        assertEquals("阶段A", chain.currentPrimary().description());
        assertTrue(chain.satisfiedStages().isEmpty());
    }

    // ===== 坏 JSON → 空仓库，不抛 =====
    @Test
    void corruptedFactsJsonYieldsEmptyStore() {
        assertEquals(0, CompletedFactStore.fromJson("{broken").stageCount());
        assertEquals(0, CompletedFactStore.fromJson("not json").stageCount());
    }

    // ===== 全阶段命中（COMPLETED）时 snapshot()/toJson() 不越界不抛 =====
    @Test
    void completedChainSnapshotAndJsonDoNotThrow() {
        Goal goal = stagedGoal("goal-abc12345", "击败末影龙", "阶段A", "阶段B");
        CompletedFactStore store = new CompletedFactStore();
        store.recordStage(goal, "阶段A", 1L, "c");
        store.recordStage(goal, "阶段B", 2L, "c");
        TaskChain chain = new TaskChain(goal, store.satisfiedStageKeys(goal));
        assertEquals(com.dwinovo.numen.rdd.api.PrimaryGoalStatus.COMPLETED, chain.primaryStatus());
        assertDoesNotThrow(chain::snapshot);
        assertDoesNotThrow(chain::toJson);
    }

    // =====================================================================
    // T2 / CF-01：辅助完成留档（CompletedFactStore.subtasks）稳定性
    // 2026-11-04 追加。根因：键里用 subtasks.size() 当唯一序号，
    // 到了 256 上限后 size 被淘汰回 256，新记录反复覆盖同一个槽。
    // =====================================================================

    @Test
    void subtaskArchiveKeepsExactlyTheNewest256() {
        Goal goal = stagedGoal("goal-abc12345", "击败末影龙", "阶段A");
        CompletedFactStore store = new CompletedFactStore();
        for (int i = 1; i <= 300; i++) {
            store.recordSubtask(goal, "阶段A", "subtask-" + i, 1000L + i);
        }
        List<String> kept = subtaskRaws(store.toJson());
        assertEquals(256, kept.size(), "只留 256 条");
        assertEquals("subtask-45", kept.get(0), "最旧的 44 条应被淘汰");
        assertEquals("subtask-300", kept.get(kept.size() - 1), "最新一条必须在");
        for (int i = 45; i <= 300; i++) {
            assertTrue(kept.contains("subtask-" + i), "上限后仍在丢记录: subtask-" + i + " 不在了");
        }
    }

    @Test
    void importedSubtaskArchiveDoesNotCollideWithNewRecords() {
        Goal goal = stagedGoal("goal-abc12345", "击败末影龙", "阶段A");
        CompletedFactStore seed = new CompletedFactStore();
        for (int i = 1; i <= 100; i++) {
            seed.recordSubtask(goal, "阶段A", "old-" + i, i);
        }
        CompletedFactStore store = CompletedFactStore.fromJson(seed.toJson());
        for (int i = 1; i <= 3; i++) {
            store.recordSubtask(goal, "阶段A", "new-" + i, 2000L + i);
        }
        List<String> kept = subtaskRaws(store.toJson());
        assertEquals(103, kept.size(), "导入 100 + 新 3 条都应留住（不得撞键覆盖）");
        assertTrue(kept.containsAll(List.of("new-1", "new-2", "new-3")), kept.toString());
        assertTrue(kept.contains("old-1") && kept.contains("old-100"), kept.toString());
    }

    @Test
    void importedSubtaskArrayIsTrimmedToNewest256() {
        CompletedFactStore store = CompletedFactStore.fromJson(subtaskJson(300));
        List<String> kept = subtaskRaws(store.toJson());
        assertEquals(256, kept.size(), "读入超限数组也要裁到最近 256");
        assertFalse(kept.contains("subtask-1"), "裁掉的是最旧那批");
        assertEquals("subtask-300", kept.get(kept.size() - 1));
    }

    @Test
    void importedStageWithoutGoalIdIsNotInheritableAndNeverThrows() {
        Goal goal = stagedGoal("goal-abc12345", "击败末影龙", "阶段A");
        CompletedFactStore seed = new CompletedFactStore();
        seed.recordStage(goal, "阶段A", 1L, "c");
        String broken = seed.toJson().replace(",\"goalId\":\"goal-abc12345\"", "");
        CompletedFactStore store = CompletedFactStore.fromJson(broken);

        Goal sameLineage = stagedGoal("goal-abc12345", "击败末影龙", "阶段A");
        assertDoesNotThrow(() -> store.satisfiedStageKeys(sameLineage), "缺 goalId 的记录不得把查询打成 NPE");
        assertFalse(store.satisfiedStageKeys(sameLineage).contains(StageKeyNormalizer.normalize("阶段A")),
                "缺 goalId 的记录不纳入可继承阶段");

        Goal similarObjective = stagedGoal("goal-abc12345", "击败末影龙任务", "阶段A");
        assertDoesNotThrow(() -> store.satisfiedStageKeys(similarObjective), "Level-2 近似路径同样不得 NPE");
    }

    @Test
    void badStageRecordDoesNotWipeHealthyRecords() {
        Goal goal = stagedGoal("goal-abc12345", "击败末影龙", "阶段A");
        CompletedFactStore seed = new CompletedFactStore();
        seed.recordStage(goal, "阶段A", 1L, "c");
        String broken = seed.toJson().replace("\"stages\":[", "\"stages\":[123,");
        CompletedFactStore store = CompletedFactStore.fromJson(broken);
        assertEquals(1, store.stageCount(), "一条坏记录不得把整仓清空");
        assertTrue(store.satisfiedStageKeys(goal).contains(StageKeyNormalizer.normalize("阶段A")),
                "正常记录必须还能被查到");
    }

    @Test
    void mixedLineageAndDuplicateTimestampsKeepEveryEvent() {
        Goal a = stagedGoal("goal-abc12345", "击败末影龙", "阶段A");
        Goal b = stagedGoal("goal-abc12345", "建造基地", "阶段A");
        CompletedFactStore store = new CompletedFactStore();
        for (int i = 1; i <= 3; i++) {
            store.recordSubtask(a, "阶段A", "a-" + i, 500L);   // 同一个时间戳
            store.recordSubtask(b, "阶段A", "b-" + i, 500L);
        }
        List<String> kept = subtaskRaws(store.toJson());
        assertEquals(6, kept.size(), "重复时间戳不是同一个事件，不能合并: " + kept);
    }

    private static List<String> subtaskRaws(String json) {
        List<String> out = new java.util.ArrayList<>();
        com.google.gson.JsonArray arr = com.google.gson.JsonParser.parseString(json)
                .getAsJsonObject().getAsJsonArray("subtasks");
        for (com.google.gson.JsonElement el : arr) {
            out.add(el.getAsJsonObject().get("rawSubtask").getAsString());
        }
        return out;
    }

    private static String subtaskJson(int count) {
        StringBuilder sb = new StringBuilder("{\"version\":1,\"stages\":[],\"subtasks\":[");
        for (int i = 1; i <= count; i++) {
            if (i > 1) sb.append(',');
            sb.append("{\"lineageId\":\"goal-abc12345#x\",\"stageKey\":\"stageA\",\"rawSubtask\":\"subtask-")
              .append(i).append("\",\"at\":").append(i).append('}');
        }
        return sb.append("]}").toString();
    }
}
