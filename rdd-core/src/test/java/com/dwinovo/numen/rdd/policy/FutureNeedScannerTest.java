package com.dwinovo.numen.rdd.policy;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「路上顺手看见后面要用的东西」（{@link FutureNeedScanner}）：2026-10-28 主人第六点。
 *
 * <p>它只报"看见了什么"，不改链、不下结论 —— 所以这里的断言重点是：
 * 分类（背包/附近容器）、够不够、有界、以及"读不到 ≠ 没有"。
 */
class FutureNeedScannerTest {

    private static PrimaryGoal stage(String id, String theme, AssetRequirement... reqs) {
        return PrimaryGoal.unexpanded(id, theme, List.of(reqs));
    }

    @Test
    void reportsInventoryAndNearbySeparatelyAndMarksWhetherTheGateIsAlreadyEnough() {
        List<PrimaryGoal> future = List.of(
                stage("p2", "石器时代",
                        new AssetRequirement("minecraft:iron_pickaxe", 1),
                        new AssetRequirement("minecraft:obsidian", 10)),
                stage("p3", "末地", new AssetRequirement("minecraft:ender_pearl", 12)));
        Map<String, Integer> inventory = Map.of("minecraft:iron_pickaxe", 1, "minecraft:ender_pearl", 3);
        Map<String, Integer> nearby = Map.of("minecraft:obsidian", 10);

        List<FutureNeedScanner.Sighting> hits = FutureNeedScanner.scan(future, inventory, nearby);

        assertEquals(3, hits.size());
        assertEquals("minecraft:iron_pickaxe", hits.get(0).assetKey());
        assertEquals(FutureNeedScanner.Where.INVENTORY, hits.get(0).where());
        assertTrue(hits.get(0).enough(), "1/1 已够那一级的门");

        assertEquals("minecraft:obsidian", hits.get(1).assetKey());
        assertEquals(FutureNeedScanner.Where.NEARBY, hits.get(1).where());
        assertTrue(hits.get(1).enough(), "箱子里的也算看见（她走过去就能拿）");

        assertEquals("minecraft:ender_pearl", hits.get(2).assetKey());
        assertFalse(hits.get(2).enough(), "3/12 不够 —— 也要报，规划器据此决定'值不值得先去刷'");
        assertTrue(hits.get(2).render().contains("3/12"));
    }

    @Test
    void thingsWithZeroSeenAreNotReported() {
        List<PrimaryGoal> future = List.of(stage("p2", "下界",
                new AssetRequirement("minecraft:netherite_ingot", 1)));
        assertTrue(FutureNeedScanner.scan(future, Map.of(), Map.of()).isEmpty(),
                "没看见就不报（否则列表里全是 0，等于噪音）");
    }

    @Test
    void emptyOrUnknownInputsAreSafe() {
        assertTrue(FutureNeedScanner.scan(null, Map.of(), Map.of()).isEmpty());
        assertTrue(FutureNeedScanner.scan(List.of(), Map.of("minecraft:dirt", 5), Map.of()).isEmpty());
        assertTrue(FutureNeedScanner.scan(
                List.of(stage("p2", "x", new AssetRequirement("minecraft:dirt", 1))), null, null).isEmpty());
    }

    @Test
    void isBoundedEvenWhenEverythingIsSatisfied() {
        List<PrimaryGoal> future = new ArrayList<>();
        for (int s = 0; s < 8; s++) {
            AssetRequirement[] reqs = new AssetRequirement[6];
            for (int i = 0; i < reqs.length; i++) {
                reqs[i] = new AssetRequirement("minecraft:item_" + s + "_" + i, 1);
            }
            future.add(stage("p" + (s + 2), "阶段" + s, reqs));
        }
        Map<String, Integer> inventory = new java.util.LinkedHashMap<>();
        for (int s = 0; s < 8; s++) {
            for (int i = 0; i < 6; i++) {
                inventory.put("minecraft:item_" + s + "_" + i, 1);
            }
        }
        List<FutureNeedScanner.Sighting> hits = FutureNeedScanner.scan(future, inventory, Map.of());
        assertEquals(FutureNeedScanner.MAX_SIGHTINGS, hits.size(),
                "有界：上下文里最多这么多条，超出的不挤进来");
    }
}
