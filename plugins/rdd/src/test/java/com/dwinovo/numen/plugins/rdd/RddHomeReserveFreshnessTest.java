package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.policy.HomeStage;
import com.dwinovo.numen.rdd.policy.HomeSummary;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    void staleButFullReserveMustNotCountAsStocked() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = baseRegistry(now - TTL - 1, fullReserve());

        assertFalse(RddHomeFacts.reserveKnown(registry, now), "过期读数不算 known");
        assertNotEquals(HomeStage.STOCKED, RddHomeFacts.stageOf(registry, now), "过期足量不得进 STOCKED");
        assertEquals(List.of(), RddHomeFacts.reserveShortfall(registry, now), "未知沿用空列表协议");

        String block = RddHomeFacts.blockFor(registry, null, now);
        assertTrue(block.contains("reserve=UNKNOWN"), block);
        assertTrue(block.contains("blocking=reserve_unobserved"), block);
        assertTrue(block.contains("next=scan_reserve_containers"), block);
    }

    @Test
    void ttlBoundaryAgreesAcrossEveryEntryPoint() {
        long now = 1_700_000_000_000L;
        AssetRegistry atTtl = baseRegistry(now - TTL, fullReserve());
        AssetRegistry overTtl = baseRegistry(now - TTL - 1, fullReserve());

        assertTrue(RddHomeFacts.reserveKnown(atTtl, now), "age=TTL 仍可用");
        assertEquals(HomeStage.STOCKED, RddHomeFacts.stageOf(atTtl, now));
        assertTrue(RddHomeFacts.blockFor(atTtl, null, now).contains("home_stage=STOCKED"));

        assertFalse(RddHomeFacts.reserveKnown(overTtl, now), "age=TTL+1 不可用");
        assertNotEquals(HomeStage.STOCKED, RddHomeFacts.stageOf(overTtl, now));
        assertTrue(RddHomeFacts.blockFor(overTtl, null, now).contains("home_stage=RESPAWN_BOUND"));
    }

    @Test
    void futureObservationIsUnknownEverywhere() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = baseRegistry(now + MINUTE, fullReserve());

        assertFalse(RddHomeFacts.reserveKnown(registry, now), "时间戳在未来不可信");
        assertNotEquals(HomeStage.STOCKED, RddHomeFacts.stageOf(registry, now));
        String block = RddHomeFacts.blockFor(registry, null, now);
        assertTrue(block.contains("reserve=UNKNOWN"), block);
    }

    @Test
    void freshEmptyMapIsKnownEmptyNotUnknown() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = baseRegistry(now - MINUTE, Map.of());

        assertTrue(RddHomeFacts.reserveKnown(registry, now), "新鲜空 Map 是已知，不是未知");
        assertFalse(RddHomeFacts.reserveShortfall(registry, now).isEmpty(), "已知空要报真实缺口");
        assertNotEquals(HomeStage.STOCKED, RddHomeFacts.stageOf(registry, now));

        String block = RddHomeFacts.blockFor(registry, null, now);
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

        assertTrue(RddHomeFacts.reserveKnown(registry, now));
        List<String> gap = RddHomeFacts.reserveShortfall(registry, now);
        assertTrue(gap.contains("cooked_beef 3/8"), gap.toString());
        assertTrue(gap.contains("torch 0/8"), gap.toString());
        assertEquals(HomeStage.RESPAWN_BOUND, RddHomeFacts.stageOf(registry, now));
        assertTrue(RddHomeFacts.blockFor(registry, null, now).contains("next=stock_reserve_box"));
    }

    @Test
    void freshFullReserveKeepsThePreFixVerdict() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = baseRegistry(now - MINUTE, fullReserve());

        assertTrue(RddHomeFacts.reserveKnown(registry, now));
        assertEquals(List.of(), RddHomeFacts.reserveShortfall(registry, now));
        assertEquals(HomeStage.STOCKED, RddHomeFacts.stageOf(registry, now), "新鲜达标的结果与改前一致");
        String block = RddHomeFacts.blockFor(registry, null, now);
        assertTrue(block.contains("home_stage=STOCKED"), block);
        assertTrue(block.contains("reserve=OK"), block);
    }

    @Test
    void noBaseIsUnknownNotEmpty() {
        long now = 1_700_000_000_000L;
        AssetRegistry empty = new AssetRegistry();

        assertEquals(HomeStage.NO_HOME, RddHomeFacts.stageOf(empty, now));
        assertFalse(RddHomeFacts.reserveKnown(empty, now));
        assertEquals(List.of(), RddHomeFacts.reserveShortfall(empty, now));
        String block = RddHomeFacts.blockFor(empty, null, now);
        assertTrue(block.contains("home_stage=NO_HOME"), block);
        assertTrue(block.contains("reserve=UNKNOWN"), block);
        assertFalse(block.contains("reserve=0"), "未知不能写成 0: " + block);
    }

    @Test
    void newFreshObservationRecoversWithoutPermanentLock() {
        long now = 1_700_000_000_000L;
        AssetRegistry registry = baseRegistry(now - TTL - MINUTE, fullReserve());
        assertNotEquals(HomeStage.STOCKED, RddHomeFacts.stageOf(registry, now));

        observeBase(registry, now, fullReserve());   // 重新扫一次箱子
        assertTrue(RddHomeFacts.reserveKnown(registry, now));
        assertEquals(HomeStage.STOCKED, RddHomeFacts.stageOf(registry, now));
        assertTrue(RddHomeFacts.blockFor(registry, null, now).contains("reserve=OK"));
    }

    @Test
    void displayAndStageNeverDisagree() {
        long now = 1_700_000_000_000L;
        for (long age : new long[]{0L, MINUTE, TTL - 1, TTL, TTL + 1, TTL + 30 * MINUTE}) {
            AssetRegistry registry = baseRegistry(now - age, fullReserve());
            boolean known = RddHomeFacts.reserveKnown(registry, now);
            boolean renderedUnknown = RddHomeFacts.blockFor(registry, null, now).contains("reserve=UNKNOWN");
            boolean stocked = RddHomeFacts.stageOf(registry, now) == HomeStage.STOCKED;
            assertEquals(!known, renderedUnknown, "age=" + age + " 展示与判定必须同口径");
            assertEquals(known, stocked, "age=" + age + " 阶段必须与 known 同口径");
        }
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
