package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P1.5 唯一规划资产口径合成测试：缓存为底、注册表 OBSERVED 覆盖、LOST/UNKNOWN 只提示不清空。 */
class PlanningAssetSnapshotTest {

    private static void inv(AssetRegistry r, String id, Number count) {
        r.apply(new Observation("o-" + id, "inventory_scan", "test", "world", 1L, Map.of("count", count)),
                id, AssetScope.GLOBAL, null);
    }

    @Test void registryTruthOverridesCachedInventory() {
        AssetRegistry r = new AssetRegistry();
        inv(r, "minecraft:iron_ingot", 2);          // 真实只剩 2
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:iron_ingot", 7), r);
        assertEquals(2, snap.availableCounts().get("minecraft:iron_ingot")); // 注册表覆盖缓存
    }

    @Test void invalidAssetsAreReportedLostButNotErasedFromCache() {
        // 2026-09-25 修正：死亡失效是"过时判定"，不得清空缓存仍持有的条目——
        // 否则重规划在重扫回 OBSERVED 之前会把已有全套装备的人当成两手空空。
        AssetRegistry r = new AssetRegistry();
        inv(r, "minecraft:diamond_pickaxe", 1);
        r.invalidateByType("inventory_scan");        // 死亡失效
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:diamond_pickaxe", 1), r);
        assertEquals(1, snap.availableCounts().get("minecraft:diamond_pickaxe")); // 缓存仍持有 → 仍算可用
        assertEquals(1, snap.lostIds().size());      // 但如实记 lost 供提示
        assertEquals("minecraft:diamond_pickaxe", snap.lostIds().get(0));
    }

    @Test void unknownAssetsAreReportedButNotErasedFromCache() {
        AssetRegistry r = new AssetRegistry();
        inv(r, "minecraft:bread", 3);
        r.markUnknown("minecraft:bread");
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:bread", 3), r);
        assertEquals(3, snap.availableCounts().get("minecraft:bread")); // 只提示"先核实"，不清空
        assertEquals(1, snap.unknownIds().size());
    }

    @Test void emptyRegistryFallsBackToCached() {
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:oak_log", 4), new AssetRegistry());
        assertEquals(4, snap.availableCounts().get("minecraft:oak_log"));
        assertTrue(snap.lostIds().isEmpty());
    }

    @Test void worldAssetsDoNotEnterInventoryCounts() {
        AssetRegistry r = new AssetRegistry();
        r.apply(new Observation("b", "world_base", "test", "world", 1L, Map.of()),
                "world:base", AssetScope.GLOBAL, null);
        var snap = PlanningAssetSnapshot.from(Map.of(), r);
        assertTrue(snap.availableCounts().isEmpty());
    }

    @Test void nonIntegerObservedCountKeepsCachedValue() {
        // 修正：OBSERVED 计数缺失/非法时保留缓存原值（新观测是加法，不是清空）。
        AssetRegistry r = new AssetRegistry();
        inv(r, "minecraft:dirt", 1.5);
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:dirt", 5), r);
        assertEquals(5, snap.availableCounts().get("minecraft:dirt"));
    }
}
