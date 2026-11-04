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
}
