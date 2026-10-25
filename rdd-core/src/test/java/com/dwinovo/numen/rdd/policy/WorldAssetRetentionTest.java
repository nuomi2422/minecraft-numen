package com.dwinovo.numen.rdd.policy;

import com.dwinovo.numen.rdd.policy.WorldAssetRetention.Action;
import com.dwinovo.numen.rdd.policy.WorldAssetRetention.Decision;
import com.dwinovo.numen.rdd.policy.WorldAssetRetention.Item;
import com.dwinovo.numen.rdd.policy.WorldAssetRetention.Kind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-B（81 号 §5.1 收窄版）：基地不被噪声挤出，陈旧实体可解释地过期。
 *
 * <p>这四条对应验收卡 B 的四条判据（离线部分）：
 * B1 噪声超上限时基地仍在；B2 TTL 边界（23999 留 / 24000 走）；
 * B3 无 tick 元数据的旧条目给宽限（不一口气全删）；B4 同类里旧的先走、基地永远不入选。
 */
class WorldAssetRetentionTest {

    private static Item it(String id, Kind kind, long tick, boolean observed) {
        return new Item(id, kind, tick, observed);
    }

    private static Map<String, Decision> byId(List<Decision> ds) {
        return ds.stream().collect(Collectors.toMap(Decision::assetId, d -> d, (a, b) -> a));
    }

    @Test
    void b1_baseSurvivesEntityFlood() {
        List<Item> items = new ArrayList<>();
        items.add(it("base|overworld|1", Kind.BASE, 10L, true));          // 最老的一条
        for (int i = 0; i < 200; i++) items.add(it("entity|overworld|villager|" + i, Kind.ENTITY, 100L + i, true));

        List<Decision> ds = WorldAssetRetention.decide(items, 128, 1_000L, 24_000L);
        Map<String, Decision> m = byId(ds);

        assertEquals(Action.KEEP, m.get("base|overworld|1").action(), "基地永不入选，哪怕它最老");
        long keptEntities = ds.stream().filter(d -> d.kind() == Kind.ENTITY && d.action() == Action.KEEP).count();
        assertEquals(127, keptEntities, "127 个实体位 + 1 个基地 = 128");
        assertEquals(73, ds.stream().filter(d -> d.action() == Action.EVICT_OVER_CAPACITY).count(), "200-(128-1)=73");
        assertTrue(ds.stream().noneMatch(d -> d.kind() == Kind.BASE && d.action() != Action.KEEP));
    }

    @Test
    void b2_entityTtlBoundaryIsExact() {
        List<Item> items = List.of(
                it("entity|fresh", Kind.ENTITY, 1_000L - 23_999L, true),
                it("entity|stale", Kind.ENTITY, 1_000L - 24_000L, true));
        Map<String, Decision> m = byId(WorldAssetRetention.decide(items, 128, 1_000L, 24_000L));
        assertEquals(Action.KEEP, m.get("entity|fresh").action(), "差 1 tick 还没到期");
        assertEquals(Action.EXPIRE_TTL, m.get("entity|stale").action(), "满 24000 tick 才过期");
        assertTrue(m.get("entity|stale").reason().contains("24000"));
    }

    @Test
    void b3_unknownAgeGetsGraceInsteadOfBeingWiped() {
        // 重启后旧条目没有 tick 元数据：本拍不接受淘汰，从"首次看到"重新计龄。
        List<Item> items = List.of(
                it("entity|legacy", Kind.ENTITY, 0L, false),
                it("village|legacy", Kind.VILLAGE, 0L, false));
        Map<String, Decision> m = byId(WorldAssetRetention.decide(items, 128, 1_000L, 24_000L));
        assertEquals(Action.KEEP, m.get("entity|legacy").action());
        assertTrue(m.get("entity|legacy").reason().contains("age_unknown_grace"));
        assertEquals(Action.KEEP, m.get("village|legacy").action());
    }

    @Test
    void b4_victimOrderPutsEntitiesFirstAndRespectsRank() {
        List<Item> items = List.of(
                it("base|x", Kind.BASE, 1L, true),
                it("structure|old", Kind.STRUCTURE, 2L, true),
                it("village|old", Kind.VILLAGE, 3L, true),
                it("machine|old", Kind.MACHINE, 4L, true),
                it("entity|old", Kind.ENTITY, 5L, true),
                it("entity|newer", Kind.ENTITY, 9L, true));
        // 上限压到 3：先扔实体（2 个）→ 再扔机器（1 个），村庄/结构/基地留下
        List<Decision> ds = WorldAssetRetention.decide(items, 3, 100L, 24_000L);
        Map<String, Decision> m = byId(ds);
        assertEquals(Action.EVICT_OVER_CAPACITY, m.get("entity|old").action());
        assertEquals(Action.EVICT_OVER_CAPACITY, m.get("entity|newer").action());
        assertEquals(Action.EVICT_OVER_CAPACITY, m.get("machine|old").action());
        assertEquals(Action.KEEP, m.get("village|old").action());
        assertEquals(Action.KEEP, m.get("structure|old").action());
        assertEquals(Action.KEEP, m.get("base|x").action());
        assertFalse(ds.isEmpty());
    }

    @Test
    void b5_allBaseOverCapacityKeepsEverythingRatherThanDeletingHome() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) items.add(it("base|" + i, Kind.BASE, i, true));
        List<Decision> ds = WorldAssetRetention.decide(items, 2, 100L, 24_000L);
        assertTrue(ds.stream().allMatch(d -> d.action() == Action.KEEP), "宁可能力超限，也不删基地");
    }
}
