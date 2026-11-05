package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 039 U3：装备判定的三态口径。
 *
 * <p>最重要的一条：**读不到 ≠ 没有**。不可核验必须单独一个状态，不能被当成"满足"。
 */
class RddConditionTest {

    private static final List<String> GEAR = List.of(
            "minecraft:iron_helmet", "minecraft:iron_chestplate",
            "minecraft:iron_leggings", "minecraft:iron_boots");

    @Test
    void fullSetIsMatched() {
        Map<String, Integer> have = Map.of(
                "minecraft:iron_helmet", 1, "minecraft:iron_chestplate", 1,
                "minecraft:iron_leggings", 2, "minecraft:iron_boots", 1);
        var r = RddCondition.requireAll(have, GEAR, true);
        assertEquals(RddCondition.Verdict.MATCHED, r.verdict());
        assertTrue(r.reason().contains("全部齐了"), r.reason());
    }

    @Test
    void missingPieceIsNotMatched() {
        Map<String, Integer> have = Map.of("minecraft:iron_helmet", 1, "minecraft:iron_chestplate", 1);
        var r = RddCondition.requireAll(have, GEAR, true);
        assertEquals(RddCondition.Verdict.NOT_MATCHED, r.verdict());
        assertTrue(r.reason().contains("iron_leggings"), r.reason());
        assertTrue(r.reason().contains("iron_boots"), r.reason());
    }

    @Test
    void unreadableIsNeverSatisfiedEvenWithEmptyInventory() {
        var r = RddCondition.requireAll(Map.of(), GEAR, false);
        assertEquals(RddCondition.Verdict.NOT_VERIFIABLE, r.verdict(),
                "读不到不许说'没有'，更不许说'有'");
        assertTrue(r.reason().contains("不可用"), r.reason());
    }

    @Test
    void unreadableWithFullDataStillUnverifiable() {
        Map<String, Integer> have = Map.of("minecraft:iron_helmet", 1);
        var r = RddCondition.requireAll(have, GEAR, false);
        assertEquals(RddCondition.Verdict.NOT_VERIFIABLE, r.verdict(),
                "数据看着有也不许当满足——来源不可核验");
    }

    @Test
    void emptyRequirementIsUnverifiableNotMatched() {
        var r = RddCondition.requireAll(Map.of(), List.of(), true);
        assertEquals(RddCondition.Verdict.NOT_VERIFIABLE, r.verdict(), "没有要件就没有'满足'可言");
    }

    @Test
    void nullInventoryDegradesToNotMatchedNotCrash() {
        var r = RddCondition.requireAll(null, GEAR, true);
        assertEquals(RddCondition.Verdict.NOT_MATCHED, r.verdict());
    }
    // ---- 042 R2：Readiness 驱动的判定（decide） ----

    private static final RddCondition.Observability READY =
            new RddCondition.Observability(true, false, false);

    @Test
    void decideMatchedNeedsOnlyPresence() {
        Map<String, Integer> have = Map.of(
                "minecraft:iron_helmet", 1, "minecraft:iron_chestplate", 1,
                "minecraft:iron_leggings", 1, "minecraft:iron_boots", 1);
        var r = RddCondition.decide(READY, have, GEAR, true);
        assertEquals(RddCondition.Verdict.MATCHED, r.verdict());
    }

    @Test
    void decideFullCoverageMissingItemIsNotMatched() {
        // 审计强调的那条：缺的物品根本不在 items 里，只能靠 response 级证否
        Map<String, Integer> have = Map.of("minecraft:iron_helmet", 1);
        var r = RddCondition.decide(READY, have, GEAR, true);
        assertEquals(RddCondition.Verdict.NOT_MATCHED, r.verdict(),
                "完整覆盖 + 缺件 → 必须说'缺'，不是'判不了'");
        assertTrue(r.reason().contains("iron_chestplate"), r.reason());
    }

    @Test
    void decidePartialCoverageMissingItemIsUnverifiable() {
        var partial = new RddCondition.Observability(true, false, false);
        Map<String, Integer> have = Map.of("minecraft:iron_helmet", 1);
        var r = RddCondition.decide(partial, have, GEAR, false);
        assertEquals(RddCondition.Verdict.NOT_VERIFIABLE, r.verdict(),
                "部分覆盖 + 缺件 → 不许断言'缺'");
    }

    @Test
    void decideStaleIsUnverifiable() {
        var stale = new RddCondition.Observability(false, true, false);
        var r = RddCondition.decide(stale, Map.of(), GEAR, false);
        assertEquals(RddCondition.Verdict.NOT_VERIFIABLE, r.verdict());
        assertTrue(r.reason().contains("过期"), r.reason());
    }

    @Test
    void decidePendingIsUnverifiable() {
        var pending = new RddCondition.Observability(false, false, true);
        var r = RddCondition.decide(pending, Map.of(), GEAR, false);
        assertEquals(RddCondition.Verdict.NOT_VERIFIABLE, r.verdict());
    }

    @Test
    void decideEmptyInventoryWithPartialCoverageIsNotAnAbsence() {
        // 实机反例 C：空背包 + 部分覆盖 → 不许说"空"
        var partial = new RddCondition.Observability(true, false, false);
        var r = RddCondition.decide(partial, Map.of(), GEAR, false);
        assertEquals(RddCondition.Verdict.NOT_VERIFIABLE, r.verdict());
    }

    @Test
    void decideEmptyInventoryWithFullCoverageIsAbsence() {
        var r = RddCondition.decide(READY, Map.of(), GEAR, true);
        assertEquals(RddCondition.Verdict.NOT_MATCHED, r.verdict());
    }

    @Test
    void decideWithoutRequiredIsUnverifiable() {
        var r = RddCondition.decide(READY, Map.of(), List.of(), true);
        assertEquals(RddCondition.Verdict.NOT_VERIFIABLE, r.verdict());
    }

    @Test
    void decideUnknownAggIsUnverifiable() {
        var unknown = new RddCondition.Observability(false, false, false);
        var r = RddCondition.decide(unknown, Map.of(), GEAR, true);
        assertEquals(RddCondition.Verdict.NOT_VERIFIABLE, r.verdict());
    }
    // ---- 042 R2：接线级用例（assess(status, coverage, items) → decide，不构造 Response） ----

    private static final java.util.Set<String> GEAR_SET = java.util.Set.copyOf(GEAR);

    private static com.dwinovo.numen.api.observation.WorldObservationService.Item item(
            java.util.Map<String, Object> values, java.util.Set<String> stale) {
        return new com.dwinovo.numen.api.observation.WorldObservationService.Item(
                values, java.util.Set.of(), stale);
    }

    private static RddCondition.Result viaAssess(
            com.dwinovo.numen.api.observation.WorldObservationService.Status status,
            com.dwinovo.numen.api.observation.WorldObservationService.Coverage coverage,
            java.util.List<com.dwinovo.numen.api.observation.WorldObservationService.Item> items) {
        var assessment = RddCondition.assess(status, coverage, items);
        java.util.Map<String, Integer> have = new java.util.LinkedHashMap<>();
        for (var it : items) {
            Object id = it.values().get("item");
            Object n = it.values().get("count");
            if (id instanceof String s && n instanceof Number num) have.merge(s, num.intValue(), Integer::sum);
        }
        boolean canProveAbsence = com.dwinovo.numen.api.observation.Readiness.canProveAbsence(status, coverage);
        return RddCondition.decide(RddCondition.Observability.from(assessment.worst()), have, GEAR, canProveAbsence);
    }

    @Test
    void wiringFullCoverageMissingGearIsNotMatched() {
        var r = viaAssess(com.dwinovo.numen.api.observation.WorldObservationService.Status.OK,
                com.dwinovo.numen.api.observation.WorldObservationService.Coverage.FULL,
                java.util.List.of(item(java.util.Map.of("item", "minecraft:iron_helmet", "count", 1),
                        java.util.Set.of())));
        assertEquals(RddCondition.Verdict.NOT_MATCHED, r.verdict(), "完整覆盖 + 缺件 → 说缺");
    }

    @Test
    void wiringPartialCoverageMissingGearIsUnverifiable() {
        // 这条就是判别新旧的关键：旧代码 readable=true → NOT_MATCHED；新代码必须 NOT_VERIFIABLE
        var r = viaAssess(com.dwinovo.numen.api.observation.WorldObservationService.Status.OK,
                com.dwinovo.numen.api.observation.WorldObservationService.Coverage.PARTIAL,
                java.util.List.of(item(java.util.Map.of("item", "minecraft:iron_helmet", "count", 1),
                        java.util.Set.of())));
        assertEquals(RddCondition.Verdict.NOT_VERIFIABLE, r.verdict(),
                "部分覆盖 + 缺件 → 不许断言缺（旧代码这里是 NOT_MATCHED）");
    }

    @Test
    void wiringStaleFieldIsUnverifiable() {
        var r = viaAssess(com.dwinovo.numen.api.observation.WorldObservationService.Status.OK,
                com.dwinovo.numen.api.observation.WorldObservationService.Coverage.FULL,
                java.util.List.of(item(java.util.Map.of("item", "minecraft:iron_helmet", "count", 1),
                        java.util.Set.of("item", "count"))));
        assertEquals(RddCondition.Verdict.NOT_VERIFIABLE, r.verdict(), "字段过期 → 不可核验");
    }

    @Test
    void wiringPendingIsUnverifiable() {
        var r = viaAssess(com.dwinovo.numen.api.observation.WorldObservationService.Status.PENDING,
                com.dwinovo.numen.api.observation.WorldObservationService.Coverage.UNKNOWN,
                java.util.List.of());
        assertEquals(RddCondition.Verdict.NOT_VERIFIABLE, r.verdict());
    }

    @Test
    void wiringFullSetIsMatched() {
        var items = java.util.List.of(
                item(java.util.Map.of("item", "minecraft:iron_helmet", "count", 1), java.util.Set.of()),
                item(java.util.Map.of("item", "minecraft:iron_chestplate", "count", 1), java.util.Set.of()),
                item(java.util.Map.of("item", "minecraft:iron_leggings", "count", 1), java.util.Set.of()),
                item(java.util.Map.of("item", "minecraft:iron_boots", "count", 2), java.util.Set.of()));
        var r = viaAssess(com.dwinovo.numen.api.observation.WorldObservationService.Status.OK,
                com.dwinovo.numen.api.observation.WorldObservationService.Coverage.FULL, items);
        assertEquals(RddCondition.Verdict.MATCHED, r.verdict());
    }
}
