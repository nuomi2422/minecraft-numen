package com.dwinovo.numen.rdd.core;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 167 §9.15：家锁定后，旧据点里还留着关键备用——要能作为线索说出来。 */
class StaleBasesTest {

    private static final List<String> KEYS = List.of("minecraft:iron_pickaxe", "minecraft:torch", "minecraft:cooked_beef");

    @Test
    void findsOldBaseWithCriticalItemsAndExcludesHome() {
        var all = List.of(
                new StaleBases.Candidate(358, 65, 636, 1000L, Map.of("minecraft:iron_pickaxe", 1)),  // 家
                new StaleBases.Candidate(371, 68, 715, 2000L, Map.of("minecraft:iron_pickaxe", 1, "minecraft:torch", 5)),
                new StaleBases.Candidate(10, 64, 10, 3000L, Map.of("minecraft:granite", 9)));
        var hits = StaleBases.find(all, 358, 65, 636, 8.0, KEYS, 2);
        assertEquals(1, hits.size(), "只应报旧据点，且必须真的有关键物品");
        assertEquals(371, hits.get(0).x());
    }

    @Test
    void richerBaseComesFirst() {
        var all = List.of(
                new StaleBases.Candidate(100, 64, 100, 1000L, Map.of("minecraft:torch", 2)),
                new StaleBases.Candidate(200, 64, 200, 900L, Map.of("minecraft:iron_pickaxe", 1, "minecraft:torch", 8)));
        var hits = StaleBases.find(all, 0, 0, 0, 8.0, KEYS, 2);
        assertEquals(200, hits.get(0).x(), "东西多的排前面");
    }

    @Test
    void describeIsMarkedLazyAndCarriesAge() {
        var c = new StaleBases.Candidate(371, 68, 715, 1000L, Map.of("minecraft:iron_pickaxe", 1));
        String s = StaleBases.describe(c, 1000L + 42 * 60_000L, KEYS);
        assertTrue(s.startsWith("stale_base=371,68,715 asOf=42m"), s);
        assertTrue(s.contains("iron_pickaxe×1"), s);
        assertTrue(s.contains("LAZY"), "必须标明是线索不是现状");
    }

    @Test
    void emptyInputIsSafe() {
        assertTrue(StaleBases.find(null, 0, 0, 0, 8, KEYS, 2).isEmpty());
        assertTrue(StaleBases.find(List.of(), 0, 0, 0, 8, KEYS, 2).isEmpty());
        assertEquals("", StaleBases.describe(null, 0L, KEYS));
    }
}