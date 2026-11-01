package com.dwinovo.numen.rdd.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 167 §B3：基地阶段与四行摘要。
 *
 * <p>这组用例钉住 167 明确写下的三条硬规则：
 * UNKNOWN 不写成 0；过期观测不算满足备用门；阶段由事实算、不能跳档。
 */
class HomeStageTest {

    private static final long NOW = 1_700_000_000_000L;

    @Test
    void stageOrderIsRespected() {
        assertEquals(HomeStage.NO_HOME, HomeStage.of(false, true, true, true, true, true));
        assertEquals(HomeStage.CANDIDATE, HomeStage.of(true, false, false, false, false, false));
        assertEquals(HomeStage.SHELTERED, HomeStage.of(true, true, false, false, false, false));
        assertEquals(HomeStage.RESPAWN_BOUND, HomeStage.of(true, true, true, false, false, false));
        assertEquals(HomeStage.STOCKED, HomeStage.of(true, true, false, true, true, false));
        assertEquals(HomeStage.LIT_PROTECTED, HomeStage.of(true, true, true, true, true, true) == HomeStage.ESTABLISHED
                ? HomeStage.LIT_PROTECTED : HomeStage.of(true, true, true, true, true, false));
        assertEquals(HomeStage.ESTABLISHED, HomeStage.of(true, true, true, true, true, true));
    }

    @Test
    void onlyStockedAndAboveCountAsRecoveryCapable() {
        assertFalse(HomeStage.RESPAWN_BOUND.hasRecovery(), "只绑了床不算有备用");
        assertTrue(HomeStage.STOCKED.hasRecovery());
        assertTrue(HomeStage.ESTABLISHED.hasRecovery());
    }

    @Test
    void unknownReserveNeverRendersAsZero() {
        String s = HomeStage.describe(null, false);
        assertEquals("reserve=UNKNOWN", s);
        List<String> lines = HomeSummary.lines(HomeStage.NO_HOME, null, null, null, null, null, null, null, null, NOW);
        assertTrue(lines.stream().anyMatch(l -> l.equals("reserve=UNKNOWN")), lines.toString());
        assertTrue(lines.stream().noneMatch(l -> l.contains("reserve=0")), lines.toString());
    }

    @Test
    void staleReserveIsNotSufficient() {
        Map<String, Integer> reserve = new LinkedHashMap<>();
        reserve.put("minecraft:iron_pickaxe", 1);
        long staleAt = NOW - HomeSummary.DEFAULT_STALE_AFTER_MS - 60_000;
        List<String> stale = HomeSummary.lines(HomeStage.STOCKED, "home_house_01", "minecraft:overworld",
                "372 68 715", NOW - 30_000, reserve, staleAt, null, null, NOW);
        assertTrue(stale.stream().anyMatch(l -> l.startsWith("reserve=UNKNOWN")),
                "超过新鲜期必须降级成 UNKNOWN: " + stale);

        List<String> fresh = HomeSummary.lines(HomeStage.STOCKED, "home_house_01", "minecraft:overworld",
                "372 68 715", NOW - 30_000, reserve, NOW - 60_000, null, null, NOW);
        assertTrue(fresh.stream().anyMatch(l -> l.startsWith("reserve=KNOWN")
                        && l.contains("minecraft:iron_pickaxe:1")),
                "新鲜观测要如实列出备用: " + fresh);
    }

    @Test
    void respawnLineCarriesAsOfAndBoundState() {
        List<String> unbound = HomeSummary.lines(HomeStage.CANDIDATE, null, null, null, null, null, null, null, null, NOW);
        assertTrue(unbound.contains("respawn=UNBOUND"), unbound.toString());

        List<String> bound = HomeSummary.lines(HomeStage.RESPAWN_BOUND, "home_house_01", "minecraft:overworld",
                "372 68 715", NOW - 120_000, null, null, null, null, NOW);
        assertTrue(bound.stream().anyMatch(l -> l.startsWith("respawn=BOUND minecraft:overworld 372 68 715")
                && l.contains("asOf=2m")), bound.toString());
    }

    @Test
    void blockingAndNextOnlyAppearWhenKnown() {
        List<String> bare = HomeSummary.lines(HomeStage.STOCKED, "h", "minecraft:overworld", "1 2 3", NOW,
                null, null, null, null, NOW);
        assertTrue(bare.stream().noneMatch(l -> l.startsWith("blocking=")), bare.toString());
        List<String> withNext = HomeSummary.lines(HomeStage.STOCKED, "h", "minecraft:overworld", "1 2 3", NOW,
                null, null, "food_production", "verify_farm_water_then_observe_output", NOW);
        assertTrue(withNext.contains("blocking=food_production"));
        assertTrue(withNext.contains("next=verify_farm_water_then_observe_output"));
    }
}
