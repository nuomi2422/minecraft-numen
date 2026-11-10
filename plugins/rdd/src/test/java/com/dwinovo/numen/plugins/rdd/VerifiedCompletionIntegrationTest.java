package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * verified 结算 → {@link CompletionFactWriter} → {@link CompletedFactStore} 的**离线生产链**验收（2026-11-10）。
 *
 * <p>生产写入点由 {@code RddPlugin.recordStageFact} 委托到 {@code CompletionFactWriter}，
 * 所以这里测的就是生产那条路，不是另写一份。不手填 verified 冒充世界事实：
 * 本测试只回答"给了 verified 之后，生产链是否真的写、写几次、写在哪、失败有没有副作用"。
 */
class VerifiedCompletionIntegrationTest {

    private static Goal goal(String id, String... stages) {
        List<PrimaryGoal> primaries = new java.util.ArrayList<>();
        for (int i = 0; i < stages.length; i++) {
            primaries.add(PrimaryGoal.unexpanded("primary-" + i, stages[i], List.of()));
        }
        return new Goal(id, "integration", primaries);
    }

    @Test void verifiedSettlementIsWrittenToDiskAndReloaded(@TempDir Path dir) {
        UUID companion = UUID.randomUUID();
        Goal g = goal("goal-int-1", "iron stage", "diamond stage");
        CompletedFactStore store = new CompletedFactStore();

        assertTrue(CompletionFactWriter.persist(dir, companion, store, g, "iron stage", 1_760_000_000_000L));

        assertTrue(Files.isRegularFile(dir.resolve(companion + ".json")), "生产写入点必须真的落盘");
        CompletedFactStore reloaded = RddFactStore.load(dir, companion);
        assertEquals(1, reloaded.stageCount());
        assertFalse(reloaded.satisfiedStageKeys(g).isEmpty(), "重载后仍认这个阶段已完成");
    }

    @Test void missingInputWritesNothing(@TempDir Path dir) {
        UUID companion = UUID.randomUUID();
        Goal g = goal("goal-int-2", "iron stage");
        CompletedFactStore store = new CompletedFactStore();
        assertFalse(CompletionFactWriter.persist(dir, null, store, g, "iron stage", 1L));
        assertFalse(CompletionFactWriter.persist(dir, companion, null, g, "iron stage", 1L));
        assertFalse(CompletionFactWriter.persist(dir, companion, store, null, "iron stage", 1L));
        assertFalse(CompletionFactWriter.persist(dir, companion, store, g, null, 1L));
        assertFalse(Files.exists(dir.resolve(companion + ".json")), "非法入参不得留下文件");
    }

    @Test void repeatedSettlementForSameStageIsIdempotent(@TempDir Path dir) {
        UUID companion = UUID.randomUUID();
        Goal g = goal("goal-int-4", "iron stage", "diamond stage");
        CompletedFactStore store = new CompletedFactStore();
        assertTrue(CompletionFactWriter.persist(dir, companion, store, g, "iron stage", 1L));
        assertTrue(CompletionFactWriter.persist(dir, companion, store, g, "iron stage", 2L));
        CompletedFactStore reloaded = RddFactStore.load(dir, companion);
        assertEquals(1, reloaded.stageCount(), "同一阶段重复结算不得写成两条");
    }

    @Test void otherGoalLineageDoesNotInheritCompletion(@TempDir Path dir) {
        UUID companion = UUID.randomUUID();
        Goal first = goal("goal-int-5", "iron stage", "diamond stage");
        CompletedFactStore store = new CompletedFactStore();
        assertTrue(CompletionFactWriter.persist(dir, companion, store, first, "iron stage", 1L));

        Goal unrelated = goal("goal-int-6", "iron stage", "diamond stage");
        CompletedFactStore reloaded = RddFactStore.load(dir, companion);
        assertTrue(reloaded.satisfiedStageKeys(unrelated).isEmpty(), "另一条目标血缘不得继承别人的完成事实");
        assertFalse(reloaded.satisfiedStageKeys(first).isEmpty());
    }

    /**
     * verified 不能凭空给：先过生产的条件核验入口 {@code RddSettleStageTool.unmetConditions}，
     * 只有它返回空（条件真满足）才允许结算并写库；不满足时一个字节都不写。
     *
     * <p>世界事实来源仍是 {@code RddDetector}（需要 Minecraft 运行时），这里用谓词替代它，
     * 所以本测试证明的是"条件核验入口→结算→写库"这段链路，不代表实机世界事实已验。
     */
    @Test void verifiedOnlyAfterConditionsReportedMet(@TempDir Path dir) {
        UUID companion = UUID.randomUUID();
        Subtask diamond = Subtask.hardCoded("a", "采钻石",
                java.util.Map.of("type", "item", "item", "minecraft:diamond", "minimum", 1));
        Goal g = new Goal("goal-int-8", "integration",
                List.of(new PrimaryGoal("primary-0", "iron stage", List.of(diamond))));
        CompletedFactStore store = new CompletedFactStore();

        // 走**生产条件入口** RddDetector.conditionMatches（type=item 分支不碰 ap，可传 null）：
        // counts 为空 = 背包没有；有 1 颗 = 满足；"容器/别处有"不进 counts，因此仍判不满足。
        java.util.function.Predicate<Subtask> fromInventory = s ->
                RddDetector.conditionMatches(null, s, java.util.Map.of("minecraft:diamond", 1));
        java.util.function.Predicate<Subtask> fromChestOnly = s ->
                RddDetector.conditionMatches(null, s, java.util.Map.of());
        assertTrue(fromInventory.test(diamond), "背包有 1 颗应判满足");
        assertFalse(fromChestOnly.test(diamond), "只有箱子有、背包没有 → 不得判完成");

        // 条件不满足：核验入口必须报出 unmet，此时不得写入。
        List<String> unmet = RddSettleStageTool.unmetConditions(g.primaryGoals().get(0), g, false, s -> false);
        assertEquals(List.of("a"), unmet);
        assertFalse(Files.exists(dir.resolve(companion + ".json")));

        // 条件满足：核验通过 → 结算 → 生产写入点落盘。
        TaskChain chain = new TaskChain(g);
        chain.startCurrent();
        List<String> met = RddSettleStageTool.unmetConditions(chain.currentPrimary(), chain.goal(), false, fromInventory);
        assertTrue(met.isEmpty(), "条件满足时核验入口应返回空");
        chain.settlePrimaryByFact(chain.currentPrimary().id(), "世界事实已核验", met.isEmpty());
        assertFalse(chain.satisfiedStages().isEmpty(), "核验通过才写入满意阶段");
        assertTrue(CompletionFactWriter.persist(dir, companion, store, chain.goal(), chain.currentPrimary().description(), 1L));
        assertTrue(Files.isRegularFile(dir.resolve(companion + ".json")));
        assertFalse(RddFactStore.load(dir, companion).satisfiedStageKeys(g).isEmpty());
    }

    @Test void failedSaveDoesNotClaimSuccess(@TempDir Path dir) {
        UUID companion = UUID.randomUUID();
        Goal g = goal("goal-int-7", "iron stage");
        CompletedFactStore store = new CompletedFactStore();
        // 用"目录位置被一个普通文件占住"制造不可写：createDirectories 会抛 IOException。
        Path blocked = dir.resolve("blocked-as-file");
        assertDoesNotThrow(() -> {
            Files.writeString(blocked, "x");
            assertFalse(CompletionFactWriter.persist(blocked, companion, store, g, "iron stage", 1L),
                    "写盘失败必须返回 false，不能假装成功");
        });
    }
}
