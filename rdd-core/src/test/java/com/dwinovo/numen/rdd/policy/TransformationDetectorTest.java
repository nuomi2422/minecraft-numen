package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G1c-2 转化检测契约（75号 §3.2：转化不等于丢失）。
 *
 * <p>钉四件事：按配方比例识别（1:4 / 2:4）、只增不减不算（拾取 ≠ 合成）、
 * 半份不报（n<1）、多规则可同时命中。
 */
class TransformationDetectorTest {

    @Test
    void detectsLogToPlanksByRecipeRatio() {
        Map<String, Integer> prev = Map.of("minecraft:oak_log", 7, "minecraft:oak_planks", 3);
        Map<String, Integer> next = Map.of("minecraft:oak_log", 5, "minecraft:oak_planks", 11);

        var found = TransformationDetector.detect(prev, next);
        assertEquals(1, found.size(), found.toString());
        var t = found.get(0);
        assertEquals("log→planks", t.kind());
        assertEquals(Map.of("minecraft:oak_log", 2), t.inputs());
        assertEquals(Map.of("minecraft:oak_planks", 8), t.outputs(), "2 原木 ×4 = 8 木板");
        assertTrue(t.describe().contains("minecraft:oak_log×2"), t.describe());
    }

    @Test
    void planksToSticksUsesTwoToFourRatio() {
        Map<String, Integer> prev = Map.of("minecraft:oak_planks", 10);
        Map<String, Integer> next = Map.of("minecraft:oak_planks", 6, "minecraft:stick", 8);

        var found = TransformationDetector.detect(prev, next);
        var sticks = found.stream().filter(x -> x.kind().equals("planks→sticks")).findFirst().orElseThrow();
        assertEquals(Map.of("minecraft:oak_planks", 4), sticks.inputs());
        assertEquals(Map.of("minecraft:stick", 8), sticks.outputs(), "4 木板 = 2 份 ×2 → 8 木棍");
    }

    @Test
    void pickupWithoutConsumptionIsNotATransformation() {
        // 只捡到木板、原木没少 → 不是合成（可能是拾取/箱子取物）
        Map<String, Integer> prev = Map.of("minecraft:oak_log", 7, "minecraft:oak_planks", 3);
        Map<String, Integer> next = Map.of("minecraft:oak_log", 7, "minecraft:oak_planks", 11);

        assertTrue(TransformationDetector.detect(prev, next).isEmpty(),
                "输入没减少就不能报转化（否则把拾取当合成）");
    }

    @Test
    void halfBatchIsNotReported() {
        // 少 1 原木但只多 2 木板（1:4 不够一份半）→ 0 份，不报
        Map<String, Integer> prev = Map.of("minecraft:oak_log", 7);
        Map<String, Integer> next = Map.of("minecraft:oak_log", 6, "minecraft:oak_planks", 2);
        assertTrue(TransformationDetector.detect(prev, next).isEmpty(),
                "n<1 不许报（不编造半份证据）");
    }

    @Test
    void multipleRulesCanFireInOneWindow() {
        Map<String, Integer> prev = Map.of(
                "minecraft:oak_log", 5, "minecraft:cobblestone", 10, "minecraft:sand", 4);
        Map<String, Integer> next = Map.of(
                "minecraft:oak_log", 4, "minecraft:oak_planks", 4,
                "minecraft:cobblestone", 9, "minecraft:stone", 1,
                "minecraft:sand", 2, "minecraft:glass", 2);

        var found = TransformationDetector.detect(prev, next);
        var kinds = found.stream().map(TransformationDetector.Transform::kind).toList();
        assertTrue(kinds.contains("log→planks"), kinds.toString());
        assertTrue(kinds.contains("smelt:cobblestone→stone"), kinds.toString());
        assertTrue(kinds.contains("smelt:sand→glass"), kinds.toString());
    }

    @Test
    void nullOrEmptyInputsAreSafe() {
        assertTrue(TransformationDetector.detect(null, Map.of("minecraft:oak_log", 1)).isEmpty());
        assertTrue(TransformationDetector.detect(Map.of(), Map.of("minecraft:oak_log", 1)).isEmpty());
        assertTrue(TransformationDetector.detect(Map.of("minecraft:oak_log", 1), null).isEmpty());
    }
}
