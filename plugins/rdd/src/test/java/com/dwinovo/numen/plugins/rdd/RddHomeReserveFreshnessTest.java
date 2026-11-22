package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.policy.HomeStage;
import com.dwinovo.numen.rdd.policy.HomeSummary;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TC-RESERVE-01 行为回归：直接打生产投影 {@link RddHomeFacts}，不另写一份测试专用算法。
 *
 * <p>背景（2026-11-04 源码核对）：{@code blockFor} 早就会按新鲜度降级，但
 * {@code stageOf / reserveKnown / reserveShortfall} 只看 {@code important_items} 字段在不在，
 * 于是"摘要说读数过期"和"风险门按旧库存放行"可以同时成立。本组用例钉住"同一时刻同一结论"。
 */
class RddHomeReserveFreshnessTest {

    private static final long MINUTE = 60_000L;
    private static final long TTL = HomeSummary.DEFAULT_STALE_AFTER_MS;
    private static final com.dwinovo.numen.api.ServerIdentityProvider.Snapshot ID =
            new com.dwinovo.numen.api.ServerIdentityProvider.Snapshot("world-A", "session-A");

    @BeforeEach void setAnchor() { RddHomeFacts.RESPX.set(372); RddHomeFacts.RESPY.set(68); RddHomeFacts.RESPZ.set(715); }
    @AfterEach void clearAnchor() { RddHomeFacts.RESPX.remove(); RddHomeFacts.RESPY.remove(); RddHomeFacts.RESPZ.remove(); }

    @Test
    void staleButFullReserveMustNotCountAsStocked() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = baseRegistry(now - TTL - 1, fullReserve());

        assertFalse(RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().known(), "过期读数不算 known");
        assertNotEquals(HomeStage.STOCKED, RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").stage(), "过期足量不得进 STOCKED");
        assertEquals(List.of(), RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().shortfall(), "未知沿用空列表协议");

        String block = RddHomeFacts.blockFor(registry, null, now, ID, 372, 68, 715, "minecraft:overworld");
        assertTrue(block.contains("reserve=UNKNOWN"), block);
        assertTrue(block.contains("blocking=reserve_unobserved"), block);
        assertTrue(block.contains("next=scan_reserve_containers"), block);
    }

    @Test
    void ttlBoundaryAgreesAcrossEveryEntryPoint() {
        long now = 1_700_000_000_000L;
        AssetRegistry atTtl = baseRegistry(now - TTL, fullReserve());
        AssetRegistry overTtl = baseRegistry(now - TTL - 1, fullReserve());

        assertTrue(RddHomeFacts.HomeView.of(atTtl, now, ID, 372, 68, 715, "minecraft:overworld").reserve().known(), "age=TTL 仍可用");
        assertEquals(HomeStage.STOCKED, RddHomeFacts.HomeView.of(atTtl, now, ID, 372, 68, 715, "minecraft:overworld").stage());
        assertTrue(RddHomeFacts.blockFor(atTtl, null, now, ID, 372, 68, 715, "minecraft:overworld").contains("home_stage=STOCKED"));

        assertFalse(RddHomeFacts.HomeView.of(overTtl, now, ID, 372, 68, 715, "minecraft:overworld").reserve().known(), "age=TTL+1 不可用");
        assertNotEquals(HomeStage.STOCKED, RddHomeFacts.HomeView.of(overTtl, now, ID, 372, 68, 715, "minecraft:overworld").stage());
        assertTrue(RddHomeFacts.blockFor(overTtl, null, now, ID, 372, 68, 715, "minecraft:overworld").contains("home_stage=RESPAWN_BOUND"));
    }

    @Test
    void futureObservationIsUnknownEverywhere() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = baseRegistry(now + MINUTE, fullReserve());

        assertFalse(RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().known(), "时间戳在未来不可信");
        assertNotEquals(HomeStage.STOCKED, RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").stage());
        String block = RddHomeFacts.blockFor(registry, null, now, ID, 372, 68, 715, "minecraft:overworld");
        assertTrue(block.contains("reserve=UNKNOWN"), block);
    }

    @Test
    void freshEmptyMapIsKnownEmptyNotUnknown() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = baseRegistry(now - MINUTE, Map.of());

        assertTrue(RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().known(), "新鲜空 Map 是已知，不是未知");
        assertFalse(RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().shortfall().isEmpty(), "已知空要报真实缺口");
        assertNotEquals(HomeStage.STOCKED, RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").stage());

        String block = RddHomeFacts.blockFor(registry, null, now, ID, 372, 68, 715, "minecraft:overworld");
        assertTrue(block.contains("reserve=KNOWN but no critical items"), block);
        assertTrue(block.contains("blocking=reserve_short:") || block.contains("blocking=reserve_missing"), block);
    }

    @Test
    void freshPartialKeepsRealShortfall() {
        long now = 1_700_000_000_000L;
        Map<String, Integer> partial = new LinkedHashMap<>();
        partial.put("minecraft:iron_pickaxe", 1);
        partial.put("minecraft:cooked_beef", 3);
        AssetRegistry registry = baseRegistry(now - MINUTE, partial);

        assertTrue(RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().known());
        List<String> gap = RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().shortfall();
        assertTrue(gap.contains("cooked_beef 3/8"), gap.toString());
        assertTrue(gap.contains("torch 0/8"), gap.toString());
        assertEquals(HomeStage.RESPAWN_BOUND, RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").stage());
        assertTrue(RddHomeFacts.blockFor(registry, null, now, ID, 372, 68, 715, "minecraft:overworld").contains("next=stock_reserve_box"));
    }

    @Test
    void freshFullReserveKeepsThePreFixVerdict() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = baseRegistry(now - MINUTE, fullReserve());

        assertTrue(RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().known());
        assertEquals(List.of(), RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().shortfall());
        assertEquals(HomeStage.STOCKED, RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").stage(), "新鲜达标的结果与改前一致");
        String block = RddHomeFacts.blockFor(registry, null, now, ID, 372, 68, 715, "minecraft:overworld");
        assertTrue(block.contains("home_stage=STOCKED"), block);
        assertTrue(block.contains("reserve=OK"), block);
    }

    @Test
    void noBaseIsUnknownNotEmpty() {
        long now = 1_700_000_000_000L;
        AssetRegistry empty = new AssetRegistry();

        assertEquals(HomeStage.NO_HOME, RddHomeFacts.HomeView.of(empty, now, ID, 372, 68, 715, "minecraft:overworld").stage());
        assertFalse(RddHomeFacts.HomeView.of(empty, now, ID, 372, 68, 715, "minecraft:overworld").reserve().known());
        assertEquals(List.of(), RddHomeFacts.HomeView.of(empty, now, ID, 372, 68, 715, "minecraft:overworld").reserve().shortfall());
        String block = RddHomeFacts.blockFor(empty, null, now, ID, 372, 68, 715, "minecraft:overworld");
        assertTrue(block.contains("home_stage=NO_HOME"), block);
        assertTrue(block.contains("reserve=UNKNOWN"), block);
        assertFalse(block.contains("reserve=0"), "未知不能写成 0: " + block);
    }

    @Test
    void newFreshObservationRecoversWithoutPermanentLock() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = baseRegistry(now - TTL - MINUTE, fullReserve());
        assertNotEquals(HomeStage.STOCKED, RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").stage());

        observeBase(registry, now, fullReserve());   // 重新扫一次箱子
        assertTrue(RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().known());
        assertEquals(HomeStage.STOCKED, RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").stage());
        assertTrue(RddHomeFacts.blockFor(registry, null, now, ID, 372, 68, 715, "minecraft:overworld").contains("reserve=OK"));
    }

    @Test
    void displayAndStageNeverDisagree() {
        long now = 1_700_000_000_000L;
        for (long age : new long[]{0L, MINUTE, TTL - 1, TTL, TTL + 1, TTL + 30 * MINUTE}) {
            AssetRegistry registry = baseRegistry(now - age, fullReserve());
            boolean known = RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().known();
            boolean renderedUnknown = RddHomeFacts.blockFor(registry, null, now, ID, 372, 68, 715, "minecraft:overworld").contains("reserve=UNKNOWN");
            boolean stocked = RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").stage() == HomeStage.STOCKED;
            assertEquals(!known, renderedUnknown, "age=" + age + " 展示与判定必须同口径");
            assertEquals(known, stocked, "age=" + age + " 阶段必须与 known 同口径");
        }
    }

    @Test void producerShapedBaseWithoutWorldIdentityIsNotKnownCurrent() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = baseRegistry(now - MINUTE, fullReserve());
        var entry = registry.get("world_base//372/68/715").orElseThrow();
        var old = entry.observation();
        Map<String, Object> value = new LinkedHashMap<>(old.value());
        value.remove("world_id"); value.remove("server_session");
        registry.apply(new Observation(old.observationId(), old.type(), old.source(), old.environmentId(), old.observedAt(), value),
                entry.assetId(), AssetScope.GLOBAL, null);
        assertFalse(RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().known(), "missing world and session cannot prove current reserve");
    }

    @Test void failedBedRecheckDoesNotTurnOldReserveIntoCurrentEvidence() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = baseRegistry(now - MINUTE, fullReserve());
        // Simulate UNKNOWN result applied to the existing current entry; the observation remains retained.
        registry.markUnknown("world_base//372/68/715");
        assertFalse(RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().known(), "unknown recheck is not current confirmation");
        assertTrue(registry.get("world_base//372/68/715").isPresent());
    }

    @Test void explicitlyIdentifiedFreshBaseRemainsKnownPositiveControl() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = new AssetRegistry();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", "base"); value.put("label", "home_house_01");
        value.put("dimension", "minecraft:overworld"); value.put("world_id", "world-A");
        value.put("server_session", "session-A"); value.put("x", 372); value.put("y", 68); value.put("z", 715);
        value.put("facilities", List.of("core_house")); value.put("important_items", fullReserve());
        registry.apply(new Observation("obs-base", "world_base", "rdd_world_observer", "minecraft:overworld",
                now - MINUTE, value), "world_base//372/68/715", AssetScope.GLOBAL, null);
        assertTrue(RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().known());
    }

    @Test void mismatchedWorldOrSessionIsUnknown() {
        long now = 1_700_000_000_000L;
        var registry = baseRegistry(now - MINUTE, fullReserve());
        assertFalse(RddHomeFacts.HomeView.of(registry, now,
                new com.dwinovo.numen.api.ServerIdentityProvider.Snapshot("world-B", "session-A"), 372, 68, 715, "minecraft:overworld").reserve().known());
        assertFalse(RddHomeFacts.HomeView.of(registry, now,
                new com.dwinovo.numen.api.ServerIdentityProvider.Snapshot("world-A", "session-B"), 372, 68, 715, "minecraft:overworld").reserve().known());
        assertTrue(RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().known());
    }

    @Test void invalidBaseIsNotKnownEvenWithinTtl() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = baseRegistry(now - MINUTE, fullReserve());
        registry.invalidate("world_base//372/68/715"); // defensive case, not claimed as existing producer path
        assertFalse(RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld").reserve().known());
    }

    @Test void unidentifiedReserveMustNotPermitHighRiskThroughRealHomeGate() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = baseRegistry(now - MINUTE, fullReserve());
        var view = RddHomeFacts.HomeView.of(registry, now);
        var otherwiseAllowed = new com.dwinovo.numen.rdd.policy.RiskGate.Verdict(true,
                com.dwinovo.numen.rdd.policy.RiskLevel.NETHER, List.of(), List.of());
        var verdict = com.dwinovo.numen.rdd.policy.HomeGate.enforce(otherwiseAllowed, view.stage());
        assertFalse(verdict.allowed(), "unidentified historical base must not satisfy high-risk home gate");
    }

    @Test void identifiedFreshReserveKeepsHighRiskHomeGatePositiveControl() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = new AssetRegistry();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", "base"); value.put("label", "home"); value.put("dimension", "minecraft:overworld");
        value.put("world_id", "world-A"); value.put("server_session", "session-A");
        value.put("x", 372); value.put("y", 68); value.put("z", 715);
        value.put("facilities", List.of("core_house")); value.put("important_items", fullReserve());
        registry.apply(new Observation("fresh", "world_base", "rdd_world_observer", "minecraft:overworld",
                now - MINUTE, value), "base", AssetScope.GLOBAL, null);
        var view = RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld");
        var otherwiseAllowed = new com.dwinovo.numen.rdd.policy.RiskGate.Verdict(true,
                com.dwinovo.numen.rdd.policy.RiskLevel.NETHER, List.of(), List.of());
        assertTrue(com.dwinovo.numen.rdd.policy.HomeGate.enforce(otherwiseAllowed, view.stage()).allowed());
    }

    @Test void existingFourBlockRadiusBoundaryIsInclusive() {
        long now = 1_700_000_000_000L;
        var registry = baseRegistry(now - MINUTE, fullReserve());
        assertTrue(RddHomeFacts.HomeView.of(registry, now, ID, 376, 68, 715, "minecraft:overworld").reserve().known());
        assertFalse(RddHomeFacts.HomeView.of(registry, now, ID, 377, 68, 715, "minecraft:overworld").reserve().known());
    }

    @Test void explicitOutOfRangeAnchorIsUnknown() {
        long now = 1_700_000_000_000L;
        var registry = baseRegistry(now - MINUTE, fullReserve());
        var view = RddHomeFacts.HomeView.of(registry, now, ID, 900, 68, 900, "minecraft:overworld");
        assertFalse(view.reserve().known());
    }

    @Test void currentAnchorBeatsNewerOtherBaseAndMissingAnchorIsUnknown() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = baseRegistry(now - 2 * MINUTE, Map.of());
        Map<String, Object> newer = new LinkedHashMap<>();
        newer.put("kind", "base"); newer.put("world_id", "world-A"); newer.put("server_session", "session-A");
        newer.put("dimension", "minecraft:overworld"); newer.put("x", 500); newer.put("y", 68); newer.put("z", 715);
        newer.put("facilities", List.of("core_house")); newer.put("important_items", fullReserve());
        registry.apply(new Observation("other", "world_base", "test", "minecraft:overworld", now - MINUTE, newer),
                "other-base", AssetScope.GLOBAL, null);
        try {
            RddHomeFacts.RESPX.set(372); RddHomeFacts.RESPY.set(68); RddHomeFacts.RESPZ.set(715);
            var anchored = RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld");
            assertEquals("world_base//372/68/715", anchored.base().assetId());
            assertFalse(anchored.reserve().sufficient());
            RddHomeFacts.RESPX.remove(); RddHomeFacts.RESPY.remove(); RddHomeFacts.RESPZ.remove();
            var missing = RddHomeFacts.HomeView.of(registry, now, ID, null, null, null, "minecraft:overworld");
            assertAll(
                    () -> assertFalse(missing.reserve().known(), "no current anchor must not pick newer unrelated base"),
                    () -> { var allowed = new com.dwinovo.numen.rdd.policy.RiskGate.Verdict(true,
                            com.dwinovo.numen.rdd.policy.RiskLevel.NETHER, List.of(), List.of());
                        assertFalse(com.dwinovo.numen.rdd.policy.HomeGate.enforce(allowed, missing.stage()).allowed()); });
        } finally {
            RddHomeFacts.RESPX.remove(); RddHomeFacts.RESPY.remove(); RddHomeFacts.RESPZ.remove();
        }
    }

    @Test void differentDimensionAtSameCoordinatesCannotBeCurrentBase() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = new AssetRegistry();
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("kind", "base"); other.put("world_id", "world-A"); other.put("server_session", "session-A");
        other.put("dimension", "minecraft:the_nether"); other.put("x", 372); other.put("y", 68); other.put("z", 715);
        other.put("facilities", List.of("core_house")); other.put("important_items", fullReserve());
        registry.apply(new Observation("other-dim", "world_base", "test", "minecraft:the_nether", now - MINUTE, other),
                "base-nether", AssetScope.GLOBAL, null);
        var view = RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:overworld");
        assertFalse(view.reserve().known(), "current overworld anchor must not use nether observation at same coordinates");
        var nether = RddHomeFacts.HomeView.of(registry, now, ID, 372, 68, 715, "minecraft:the_nether");
        assertTrue(nether.reserve().known(), "matching dimension remains usable");
    }

    private static Map<String, Integer> fullReserve() {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("minecraft:iron_pickaxe", 1);
        m.put("minecraft:cooked_beef", 8);
        m.put("minecraft:torch", 8);
        return m;
    }

    private static AssetRegistry baseRegistry(long observedAt, Map<String, Integer> items) {
        AssetRegistry registry = new AssetRegistry();
        observeBase(registry, observedAt, items);
        return registry;
    }

    private static void observeBase(AssetRegistry registry, long observedAt, Map<String, Integer> items) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", "base");
        value.put("world_id", "world-A"); value.put("server_session", "session-A");
        value.put("label", "home_house_01");
        value.put("dimension", "minecraft:overworld");
        value.put("x", 372);
        value.put("y", 68);
        value.put("z", 715);
        value.put("facilities", List.of("core_house"));
        value.put("important_items", items);
        registry.apply(new Observation("obs-base", "world_base", "test", "minecraft:overworld",
                observedAt, value), "world_base//372/68/715", AssetScope.GLOBAL, null);
    }
}
