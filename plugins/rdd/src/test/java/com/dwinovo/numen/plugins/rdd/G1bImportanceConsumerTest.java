package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import com.dwinovo.numen.rdd.policy.RequirementManifest;
import com.dwinovo.numen.rdd.policy.RequirementView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G1b 契约测试（75号 §16.3 G1b / IC-75-r1 §15.4 消费者接线，2026-10-20）。
 *
 * <p>钉四条链：生成侧产 importance（S40）、事件侧计 critical（在宿主侧被
 * {@code publishManifestEvent} 私有方法包住，此处钉 manifest 数据源）、
 * 渲染侧前缀可见（S41）、对账视图透传（S42）。
 *
 * <p>★ 判据纪律：本批是<b>新功能接线</b>非缺陷修复，无「先红」义务；但断言在
 * 实机（S43/S44）前冻结，实机只验「可见性到达」，不判模型行为（D09）。
 */
class G1bImportanceConsumerTest {

    private static PrimaryGoal goal() {
        return new PrimaryGoal("primary-g1b", "备料进阶", List.of(
                Subtask.hardCoded("s1", "铁锭 x5", Map.of("asset_key", "minecraft:iron_ingot", "minimum", 5)),
                Subtask.hardCoded("s2", "木头 x16", Map.of("group", "wood", "minimum", 16))),
                List.of(
                        new AssetRequirement("minecraft:oak_log", 8),      // waitFor 前置 → CRITICAL
                        new AssetRequirement("minecraft:iron_ingot", 2))); // 同键撞 condition → 取高（CRITICAL）
    }

    // ── S40 · 生成侧：waitFor → CRITICAL，condition → NORMAL，同键取高 ──

    @Test
    void s40_waitForBecomesCriticalConditionStaysNormal() {
        RequirementManifest.Manifest manifest = RddRequirementDetector.forPrimary(goal(), "goal-g1b");

        var oak = manifest.requirements().stream()
                .filter(r -> "minecraft:oak_log".equals(r.key())).findFirst().orElseThrow();
        assertEquals(RequirementManifest.Importance.CRITICAL, oak.importance(),
                "waitFor 前置 = 进本一级的门 → CRITICAL");

        var wood = manifest.requirements().stream()
                .filter(r -> "group:wood".equals(r.key())).findFirst();
        assertTrue(wood.isEmpty(), "group 条件不进 manifest（检测通道单独跟踪），只 asset_key 进");

        var iron = manifest.requirements().stream()
                .filter(r -> "minecraft:iron_ingot".equals(r.key())).findFirst().orElseThrow();
        // 本 goal() 里 iron_ingot 同键撞 waitFor(CRITICAL) + condition(NORMAL) → 按「取高」= CRITICAL。
        // 纯 NORMAL 案例由 s40_pureConditionStaysNormal 单独钉（用不带 waitFor 撞键的 goal）。
        assertEquals(RequirementManifest.Importance.CRITICAL, iron.importance(),
                "同键 waitFor + condition → 取高（CRITICAL）");
        assertEquals(5, iron.minimum(), "waitFor 的 minimum=2 与 condition 的 5 同键 → 取大（既有规则）");
    }

    @Test
    void s40_pureConditionStaysNormal() {
        // 不带 waitFor 的 goal：condition 需求必须落 NORMAL（不是万物皆 CRITICAL 的反例）
        PrimaryGoal pure = new PrimaryGoal("primary-pure", "纯干活", List.of(
                Subtask.hardCoded("s1", "石头 x10",
                        Map.of("asset_key", "minecraft:stone", "minimum", 10))), List.of());
        RequirementManifest.Manifest manifest = RddRequirementDetector.forPrimary(pure, "goal-pure");
        var stone = manifest.requirements().stream()
                .filter(r -> "minecraft:stone".equals(r.key())).findFirst().orElseThrow();
        assertEquals(RequirementManifest.Importance.NORMAL, stone.importance(),
                "没有 waitFor 撞键时 condition 需求 = NORMAL（G1b 语义：重要性来自目标关系，不是全局属性）");
    }

    @Test
    void s40_sameKeyTakesHigherImportance() {
        // waitFor 说 CRITICAL(minimum 1)、condition 说 NORMAL(minimum 5) → CRITICAL + minimum 5
        RequirementManifest.Manifest manifest = RddRequirementDetector.forPrimary(goal(), "goal-g1b");
        var iron = manifest.requirements().stream()
                .filter(r -> "minecraft:iron_ingot".equals(r.key())).findFirst().orElseThrow();
        // 本 goal() 里 iron_ingot 的 waitFor 是 minimum=2：同键撞 condition(5) → minimum 5。
        // importance：waitFor CRITICAL vs condition NORMAL → 取 CRITICAL。
        // ★ 注意 goal() 里 iron 的 waitFor minimum=2 < condition 5，但 importance 仍取高。
        assertEquals(RequirementManifest.Importance.CRITICAL, iron.importance(),
                "同键 waitFor(CRITICAL) + condition(NORMAL) → CRITICAL（两处口径必须一致，否则清单与上下文块矛盾）");
    }

    @Test
    void s40_needsOfMatchesDetectorImportance() {
        // RddFactContext.needsOf 与 RddRequirementDetector.forPrimary 的 importance 口径必须一致
        List<RddFactContext.Need> needs = RddFactContext.needsOf(goal());
        var oakNeed = needs.stream().filter(n -> "minecraft:oak_log".equals(n.key()))
                .findFirst().orElseThrow();
        assertEquals(RequirementManifest.Importance.CRITICAL, oakNeed.importance(),
                "needsOf 的 waitFor 需求也要标 CRITICAL（与 detector 同口径）");
        var ironNeed = needs.stream().filter(n -> "minecraft:iron_ingot".equals(n.key()))
                .findFirst().orElseThrow();
        assertEquals(RequirementManifest.Importance.CRITICAL, ironNeed.importance(),
                "同键取高在 needsOf 也要成立");
        var woodNeed = needs.stream().filter(RddFactContext.Need::group)
                .findFirst().orElseThrow();
        assertEquals(RequirementManifest.Importance.NORMAL, woodNeed.importance(),
                "group 需求恒 NORMAL（组是检测归纳，不是目标前置）");
    }

    // ── S41 · 渲染侧：CRITICAL 前缀 [关键]，NORMAL 无前缀，回归不破 ──

    @Test
    void s41_needsBlockPrefixesCriticalOnly() {
        List<RddFactContext.Need> needs = RddFactContext.needsOf(goal());
        // oak_log CRITICAL 未满足（0 < 8）；iron CRITICAL（撞 waitFor）未满足；wood NORMAL 未满足
        // wood 组现有量 = birch_log 4（组求和：只有 birch 一种木头在背包）
        Map<String, Integer> held = Map.of("minecraft:iron_ingot", 1, "minecraft:birch_log", 4);

        String block = RddFactContext.renderNeedsBlock(needs, held);
        assertTrue(block.contains("[关键] minecraft:oak_log ×8（现有 0）"), block);
        assertTrue(block.contains("[关键] minecraft:iron_ingot ×5（现有 1）"), block);
        assertTrue(block.contains("group:wood ×16（现有 4）"), block);
        assertFalse(block.contains("[关键] group:wood"), "NORMAL 需求不许带前缀：" + block);
        assertTrue(block.endsWith("</needs>"), block);
    }

    @Test
    void s41_allMetStillHidesBlock() {
        List<RddFactContext.Need> needs = RddFactContext.needsOf(goal());
        Map<String, Integer> held = Map.of(
                "minecraft:oak_log", 8, "minecraft:iron_ingot", 5, "minecraft:birch_log", 16);
        assertEquals("", RddFactContext.renderNeedsBlock(needs, held), "全满足 → 整块消失（回归）");
    }

    // ── S42 · 对账视图：importance 透传 + replan 渲染前缀 ──

    @Test
    void s42_viewRowCarriesImportanceThrough() {
        RequirementManifest.Manifest manifest = new RequirementManifest.Manifest("goal-v", List.of(
                new RequirementManifest.Requirement("minecraft:flint", 1, List.of(),
                        RequirementManifest.Importance.CRITICAL, 0),
                new RequirementManifest.Requirement("minecraft:stone", 32, List.of())));
        RequirementView.View view = RequirementView.build(manifest, null, null, null);

        assertEquals(2, view.rows().size());
        var flintRow = view.rows().stream()
                .filter(r -> "minecraft:flint".equals(r.requirementKey())).findFirst().orElseThrow();
        assertEquals(RequirementManifest.Importance.CRITICAL, flintRow.importance(),
                "importance 必须透传进 Row（registry=null → 持有未知，但属性不因观测缺失而丢）");
        assertEquals("CRITICAL", flintRow.toMap().get("importance"), "监测台 toMap 可见");

        var stoneRow = view.rows().stream()
                .filter(r -> "minecraft:stone".equals(r.requirementKey())).findFirst().orElseThrow();
        assertEquals(RequirementManifest.Importance.NORMAL, stoneRow.importance(),
                "三参构造（老调用）默认 NORMAL —— 兼容构造不变形");
    }

    @Test
    void s42_replanBlockPrefixesCriticalRows() {
        RequirementManifest.Manifest manifest = new RequirementManifest.Manifest("goal-r", List.of(
                new RequirementManifest.Requirement("minecraft:flint", 1, List.of(),
                        RequirementManifest.Importance.CRITICAL, 0),
                new RequirementManifest.Requirement("minecraft:stone", 32, List.of())));
        CompletedFactStore facts = new CompletedFactStore();
        AssetRegistry assets = new AssetRegistry();
        var snapshot = com.dwinovo.numen.rdd.fact.FactSnapshot.capture(
                UUID.randomUUID(), "minecraft:overworld", facts, assets, null, 1_000L, 60_000L);

        String block = RddFactContext.renderReplanBlock(manifest, facts, assets, snapshot, 31_000L);
        assertTrue(block.contains("[关键] minecraft:flint 需要 1"), block);
        assertFalse(block.contains("[关键] minecraft:stone"), "NORMAL 行无前缀：" + block);
    }

    // ── S45 · 事件侧（2026-10-22，三项验证卡问题一处置 ②）：
    //     requirement_manifest 事件带 rows[]（key/importance/minimum），监测台判据 A2'。
    //     RddMonitor 在纯 JVM 环境会 fail-soft 吞事件，所以把「行构造」抽成
    //     manifestRows 纯函数单独钉形状；超 MAX_MANIFEST_ROWS(12) 必须 truncated=true。──

    @Test
    void s45_manifestRowsCarryKeyImportanceMinimum() {
        RequirementManifest.Manifest manifest = RddRequirementDetector.forPrimary(goal(), "goal-s45");
        var rowList = RddFactContext.manifestRows(manifest);

        assertFalse(rowList.truncated(), "goal() 只有两个需求，不该截断");
        assertEquals(manifest.requirements().size(), rowList.rows().size(), "不截断时行数 = 需求数");
        var oakRow = rowList.rows().stream()
                .filter(r -> "minecraft:oak_log".equals(r.get("key"))).findFirst().orElseThrow();
        assertEquals("CRITICAL", oakRow.get("importance"), "waitFor 前置 → 事件行也标 CRITICAL");
        assertEquals(8, oakRow.get("minimum"), "minimum 透传（对齐 S40 的 detector 口径）");
        for (Map<String, Object> row : rowList.rows()) {
            assertTrue(row.containsKey("key") && row.containsKey("importance") && row.containsKey("minimum"),
                    "每行必须带 key/importance/minimum 三键（监测台判据 A2'）：" + row);
        }
    }

    @Test
    void s45_manifestRowsTruncateAtCapWithFlag() {
        // 15 条需求 > MAX_MANIFEST_ROWS(12)：rows 只落 12 条且 truncated=true（事件有界纪律）
        List<RequirementManifest.Requirement> many = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            many.add(new RequirementManifest.Requirement("minecraft:item_" + i, i + 1, List.of()));
        }
        RequirementManifest.Manifest manifest = new RequirementManifest.Manifest("goal-many", many);
        var rowList = RddFactContext.manifestRows(manifest);

        assertEquals(12, rowList.rows().size(), "行数封顶在 MAX_MANIFEST_ROWS");
        assertTrue(rowList.truncated(), "超限必须显式 truncated=true，不装作落全了");
    }
}
