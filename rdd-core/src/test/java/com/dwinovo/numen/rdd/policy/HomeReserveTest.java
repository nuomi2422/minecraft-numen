package com.dwinovo.numen.rdd.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC-RESERVE-01（2026-11-04）：同一份备用观测，展示与准入必须给出同一个"能不能当证据"的结论。
 *
 * <p>钉住的口径：
 * <ul><li>没扫到 / 过期 / 时间戳在未来 → known=false，缺口空表（未知≠缺失）；</li>
 * <li>新鲜的空 Map → known=true、KNOWN_EMPTY，不是"未知"；</li>
 * <li>过期足量不得进 STOCKED，但原始读数不被抹掉；</li>
 * <li>TTL 边界与 {@link HomeSummary#fresh} 完全一致（不做第二套 age 公式）。</li></ul>
 */
class HomeReserveTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long TTL = HomeSummary.DEFAULT_STALE_AFTER_MS;

    private static Map<String, Integer> full() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("minecraft:iron_pickaxe", 1);
        m.put("minecraft:cooked_beef", 8);
        m.put("minecraft:torch", 8);
        return m;
    }

    private static Map<String, Integer> partial() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("minecraft:iron_pickaxe", 1);
        m.put("minecraft:cooked_beef", 3);
        return m;
    }

    @Test
    void unobservedIsUnknownNotAnEmptyBox() {
        HomeReserve r = HomeReserve.unobserved();
        assertFalse(r.known(), "没扫到不能算已知");
        assertNull(r.items(), "未知时不能退化成空 Map");
        assertNull(r.rawItems());
        assertEquals(List.of(), r.shortfall(), "沿用空列表协议");
        assertFalse(r.sufficient(), "未知不算够");

        HomeStage stage = HomeStage.of(true, true, true, r, false);
        assertEquals(HomeStage.RESPAWN_BOUND, stage, "只可能停在绑床这一档");
        assertNotEquals(HomeStage.STOCKED, stage);
        List<String> lines = HomeSummary.lines(stage, "h", "minecraft:overworld", "1 2 3", NOW, r, null, null, NOW);
        assertTrue(lines.contains("reserve=UNKNOWN"), lines.toString());
    }

    @Test
    void freshEmptyMapIsKnownEmpty() {
        HomeReserve r = HomeReserve.of(Map.of(), NOW - 1_000, NOW);
        assertTrue(r.known(), "新鲜的空 Map 是'已知但空'");
        assertEquals(Map.of(), r.items());
        assertFalse(r.sufficient());
        assertEquals(3, r.shortfall().size(), r.shortfall().toString());
        assertTrue(r.shortfall().contains("cooked_beef 0/8"));

        HomeStage stage = HomeStage.of(true, true, true, r, false);
        assertEquals(HomeStage.RESPAWN_BOUND, stage, "空箱子不能到 STOCKED");
        List<String> lines = HomeSummary.lines(stage, "h", "minecraft:overworld", "1 2 3", NOW, r, null, null, NOW);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("reserve=KNOWN but no critical items")),
                lines.toString());
    }

    @Test
    void freshPartialKeepsRealShortfall() {
        HomeReserve r = HomeReserve.of(partial(), NOW - 2_000, NOW);
        assertTrue(r.known());
        List<String> gap = r.shortfall();
        assertTrue(gap.contains("cooked_beef 3/8"), gap.toString());
        assertTrue(gap.contains("torch 0/8"), gap.toString());
        assertFalse(r.sufficient());
        assertNotEquals(HomeStage.STOCKED, HomeStage.of(true, true, true, r, false));
    }

    @Test
    void freshSufficientMatchesTheOldResult() {
        HomeReserve r = HomeReserve.of(full(), NOW - 1_000, NOW);
        assertTrue(r.sufficient());
        assertEquals(List.of(), r.shortfall());
        // 与修改前"新鲜且达标"的派生完全一致：不放松也不额外收紧。
        assertEquals(HomeStage.of(true, true, true, true, true, false),
                HomeStage.of(true, true, true, r, false));
        assertEquals(HomeStage.STOCKED, HomeStage.of(true, true, true, r, false));
        List<String> lines = HomeSummary.lines(HomeStage.STOCKED, "h", "minecraft:overworld", "1 2 3",
                NOW, r, null, null, NOW);
        assertTrue(lines.stream().anyMatch(l -> l.startsWith("reserve=OK")), lines.toString());
    }

    @Test
    void ttlBoundaryIsTheSameOneEverywhere() {
        HomeReserve atTtl = HomeReserve.of(full(), NOW - TTL, NOW);
        assertTrue(atTtl.known(), "age=TTL 仍可用");
        HomeReserve overTtl = HomeReserve.of(full(), NOW - TTL - 1, NOW);
        assertFalse(overTtl.known(), "age=TTL+1 不可用");

        assertEquals(HomeStage.STOCKED, HomeStage.of(true, true, true, atTtl, false));
        assertNotEquals(HomeStage.STOCKED, HomeStage.of(true, true, true, overTtl, false));

        List<String> okLines = HomeSummary.lines(HomeStage.STOCKED, "h", "minecraft:overworld", "1 2 3",
                NOW, atTtl, null, null, NOW);
        List<String> sadLines = HomeSummary.lines(HomeStage.RESPAWN_BOUND, "h", "minecraft:overworld", "1 2 3",
                NOW, overTtl, null, null, NOW);
        assertTrue(okLines.stream().anyMatch(l -> l.startsWith("reserve=OK")), okLines.toString());
        assertTrue(sadLines.stream().anyMatch(l -> l.startsWith("reserve=UNKNOWN")), sadLines.toString());
    }

    @Test
    void futureTimestampIsUntrustworthyInBothPaths() {
        HomeReserve future = HomeReserve.of(full(), NOW + 1, NOW);
        assertFalse(future.known(), "时间戳在 now 之后不可信");
        assertNull(future.items());
        assertEquals(full(), future.rawItems(), "读数本身不删");
        assertNotEquals(HomeStage.STOCKED, HomeStage.of(true, true, true, future, false));
        List<String> viaProjection = HomeSummary.lines(HomeStage.RESPAWN_BOUND, "h", "minecraft:overworld",
                "1 2 3", NOW, future, null, null, NOW);
        assertTrue(viaProjection.stream().anyMatch(l -> l.startsWith("reserve=UNKNOWN")), viaProjection.toString());
        // 旧入参（Map + asOf）也必须走同一把尺子
        List<String> viaMap = HomeSummary.lines(HomeStage.RESPAWN_BOUND, "h", "minecraft:overworld",
                "1 2 3", NOW, full(), NOW + 1, null, null, NOW);
        assertTrue(viaMap.stream().anyMatch(l -> l.startsWith("reserve=UNKNOWN")), viaMap.toString());
    }

    @Test
    void staleSufficientReserveIsNotAdmissionEvidence() {
        HomeReserve stale = HomeReserve.of(full(), NOW - TTL - 60_000, NOW);
        assertFalse(stale.known());
        assertFalse(stale.sufficient(), "过期足量不等于够");
        assertEquals(List.of(), stale.shortfall());
        assertEquals(full(), stale.rawItems(), "过期不等于把读数删掉");

        HomeStage stage = HomeStage.of(true, true, true, stale, false);
        assertNotEquals(HomeStage.STOCKED, stage);

        // 与 RddDetector.riskGateAllowed 同构：风险门吃 known/缺口，基地门吃阶段。
        var withoutBase = RiskGate.checkWithBase(RiskLevel.NETHER, Map.of(), true, false, List.of());
        var withStale = RiskGate.checkWithBase(RiskLevel.NETHER, Map.of(), true, stale.known(), stale.shortfall());
        assertEquals(withoutBase.allowed(), withStale.allowed(), "过期读数不得额外收紧物资门");
        assertEquals(withoutBase.missing(), withStale.missing());

        var permissiveNether = new RiskGate.Verdict(true, RiskLevel.NETHER, List.of(), List.of());
        var staleEnforced = HomeGate.enforce(permissiveNether, stage);
        assertFalse(staleEnforced.allowed(), "过期足量库存不得放行下界");
        assertTrue(staleEnforced.missing().stream().anyMatch(m -> m.contains("home_stage=")),
                staleEnforced.missing().toString());

        // 同一天晚上补扫一次（新鲜足量）就该恢复，不留永久锁。
        HomeReserve rescan = HomeReserve.of(full(), NOW, NOW + 1_000);
        assertTrue(rescan.known());
        assertTrue(rescan.sufficient());
        assertEquals(HomeStage.STOCKED, HomeStage.of(true, true, true, rescan, false));
        assertTrue(HomeGate.enforce(permissiveNether, HomeStage.of(true, true, true, rescan, false)).allowed(),
                "新鲜足量后基地门不该再拦（其它门另算）");
    }

    @Test
    void displayTrimKeepsKnownState() {
        HomeReserve stale = HomeReserve.of(full(), NOW - TTL - 1, NOW);
        assertFalse(stale.withItems(Map.of("minecraft:torch", 8)).known(), "裁剪展示不得把过期洗成可用");
        HomeReserve fresh = HomeReserve.of(full(), NOW - 1_000, NOW);
        assertTrue(fresh.withItems(Map.of("minecraft:torch", 8)).known());
        assertEquals(Map.of("minecraft:torch", 8), fresh.withItems(Map.of("minecraft:torch", 8)).items());
    }
}
