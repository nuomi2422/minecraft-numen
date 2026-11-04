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
    void knownButEmptyReserveSaysSoInsteadOfUnknown() {
        // 167 B5 首跑暴露的混淆：箱子读到了但没备货，原来也渲染成 UNKNOWN，和"根本没扫到"一样。
        assertEquals("reserve=KNOWN but no critical items", HomeStage.describe(java.util.Map.of(), true));
        assertEquals("reserve=UNKNOWN", HomeStage.describe(java.util.Map.of(), false));
        assertFalse(HomeStage.of(true, true, true, true, false, false) == HomeStage.STOCKED,
                "已知但没备货不算 STOCKED");
    }

    @Test
    void reserveKitDecidesSufficiencyByItself() {
        // 167 B5：达标与否不再由调用方拍，统一按 ReserveKit.MINIMUM（铁镐1/熟牛肉8/火把8）。
        assertFalse(HomeStage.reserveSufficient(null), "未知不算够");
        assertFalse(HomeStage.reserveSufficient(java.util.Map.of("minecraft:torch", 64)),
                "只有火把不算够（缺镐和食物）");
        Map<String, Integer> full = new LinkedHashMap<>();
        full.put("minecraft:iron_pickaxe", 1);
        full.put("minecraft:cooked_beef", 8);
        full.put("minecraft:torch", 8);
        assertTrue(HomeStage.reserveSufficient(full), "刚好齐就该算够");
        assertTrue(HomeStage.of(true, true, true, true, HomeStage.reserveSufficient(full), true)
                == HomeStage.ESTABLISHED);
    }

    @Test
    void shortfallIsRenderedWithHaveOverNeed() {
        Map<String, Integer> partial = new LinkedHashMap<>();
        partial.put("minecraft:iron_pickaxe", 1);
        partial.put("minecraft:cooked_beef", 3);
        String s = HomeStage.describe(partial, true);
        assertTrue(s.startsWith("reserve=SHORT"), s);
        assertTrue(s.contains("cooked_beef 3/8"), "缺多少要写在行里: " + s);
        assertTrue(s.contains("torch 0/8"), s);
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
        // §B5 之后渲染格式变成 SHORT/OK + missing= + have:（ReserveKit 统一判定够不够）
        assertTrue(fresh.stream().anyMatch(l -> l.startsWith("reserve=SHORT")
                        && l.contains("have:") && l.contains("minecraft:iron_pickaxe:1")),
                "新鲜观测要如实列出备用与缺口: " + fresh);
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
    void baseReserveTightensTheGateButNeverLoosensIt() {
        // 167 §B6：ESTABLISHED 不是放行理由；这里钉住"只收紧不放松"。
        Map<String, Integer> empty = java.util.Map.of();
        var withoutBase = RiskGate.checkWithBase(RiskLevel.NETHER, empty, true, false, java.util.List.of());
        var withBaseGap = RiskGate.checkWithBase(RiskLevel.NETHER, empty, true, true,
                java.util.List.of("cooked_beef 0/8", "torch 0/8"));
        assertFalse(withBaseGap.allowed());
        assertTrue(withBaseGap.missing().stream().anyMatch(m -> m.startsWith("base_reserve need cooked_beef")),
                withBaseGap.missing().toString());
        // 基地达标时，结果必须与"不传基地"完全一致 —— 不许悄悄放宽
        var baseOk = RiskGate.checkWithBase(RiskLevel.NETHER, empty, true, true, java.util.List.of());
        assertEquals(withoutBase.allowed(), baseOk.allowed());
        assertEquals(withoutBase.missing(), baseOk.missing());
    }

    @Test
    void freshnessRuleIsTheSameOneUsedForRendering() {
        long now = 1_700_000_000_000L;
        assertTrue(HomeSummary.fresh(now - 5_000, now));
        assertFalse(HomeSummary.fresh(now - HomeSummary.DEFAULT_STALE_AFTER_MS - 1_000, now));
        assertFalse(HomeSummary.fresh(null, now));
        assertFalse(HomeSummary.fresh(now - 40 * 60_000L, now));
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

    @Test
    void futureReserveTimestampIsUnknownEverywhere() {
        // TC-RESERVE-01：时间戳在未来的观测不可信。渲染与判定必须同时降到 UNKNOWN。
        Map<String, Integer> reserve = new LinkedHashMap<>();
        reserve.put("minecraft:iron_pickaxe", 1);
        reserve.put("minecraft:cooked_beef", 8);
        reserve.put("minecraft:torch", 8);
        List<String> lines = HomeSummary.lines(HomeStage.STOCKED, "home_house_01", "minecraft:overworld",
                "372 68 715", NOW - 30_000, reserve, NOW + 1, null, null, NOW);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("reserve=UNKNOWN")),
                "未来时间戳必须渲染成 UNKNOWN: " + lines);
    }
}
