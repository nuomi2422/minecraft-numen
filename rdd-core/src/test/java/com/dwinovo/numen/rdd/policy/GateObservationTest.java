package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 裁决观测：把「要求」与「实有」放在同一行，用来分辨"配额高"与"键不可满足"。 */
class GateObservationTest {

    @Test
    void 要求项全部带出实有数量() {
        Map<String, Integer> required = new LinkedHashMap<>();
        required.put("minecraft:torch", 16);
        required.put("minecraft:fire_resistance_potion", 3);
        Map<String, Integer> inv = Map.of("minecraft:torch", 20);

        Map<String, Integer> o = GateObservation.observe(required, inv, 0);
        assertEquals(20, o.get("minecraft:torch"));
        assertEquals(0, o.get("minecraft:fire_resistance_potion"), "没有就是 0，不是缺键");
    }

    @Test
    void 背包里的药水键必须出现() {
        // 这条是 166 B3 的关键：要能看见 countInventory 有没有把药水映射成 *_potion。
        Map<String, Integer> required = Map.of("minecraft:fire_resistance_potion", 3);
        Map<String, Integer> inv = Map.of("minecraft:potion", 3, "minecraft:splash_potion", 1);

        Map<String, Integer> o = GateObservation.observe(required, inv, 0);
        assertTrue(o.containsKey("minecraft:fire_resistance_potion"));
        assertTrue(o.containsKey("minecraft:splash_potion"), "背包里的药水键要能直接看到");
    }

    @Test
    void 有条数上限防止日志膨胀() {
        Map<String, Integer> required = new LinkedHashMap<>();
        for (int i = 0; i < 40; i++) required.put("minecraft:item" + i, 1);
        Map<String, Integer> o = GateObservation.observe(required, Map.of(), 5);
        assertEquals(5, o.size());
    }

    @Test
    void 空输入不抛() {
        assertTrue(GateObservation.observe(null, null, 0).isEmpty());
    }
}
