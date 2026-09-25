package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** P2-A 唯一规划资产口径：实时扫描为唯一真相，注册表 inventory_scan 仅作 lost/unknown 提示。 */
class PlanningAssetSnapshotTest {

    private static void inv(AssetRegistry r, String id, Number count) {
        r.apply(new Observation("o-" + id, "inventory_scan", "test", "world", 1L, Map.of("count", count)),
                id, AssetScope.GLOBAL, null);
    }

    @Test void liveScanIsTheOnlyTruthRegistryDoesNotOverride() {
        AssetRegistry r = new AssetRegistry();
        inv(r, "minecraft:iron_ingot", 2);          // 注册表里是 2（可能过时）
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:iron_ingot", 7), r);
        assertEquals(7, snap.availableCounts().get("minecraft:iron_ingot"),
                "实时扫描为唯一真相，注册表不得覆盖");
    }

    @Test void invalidAssetsAreReportedLostButDoNotAffectAvailability() {
        AssetRegistry r = new AssetRegistry();
        inv(r, "minecraft:diamond_pickaxe", 1);
        r.invalidateByType("inventory_scan");        // 死亡失效
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:diamond_pickaxe", 1), r);
        assertEquals(1, snap.availableCounts().get("minecraft:diamond_pickaxe"), "实时扫描仍持有 → 仍算可用");
        assertEquals(1, snap.lostIds().size());      // 但如实记 lost 供提示
        assertEquals("minecraft:diamond_pickaxe", snap.lostIds().get(0));
    }

    @Test void unknownAssetsAreReportedButDoNotAffectAvailability() {
        AssetRegistry r = new AssetRegistry();
        inv(r, "minecraft:bread", 3);
        r.markUnknown("minecraft:bread");
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:bread", 3), r);
        assertEquals(3, snap.availableCounts().get("minecraft:bread"));
        assertEquals(1, snap.unknownIds().size());
    }

    @Test void emptyRegistryFallsBackToLiveScan() {
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

    @Test void availableComesOnlyFromLiveScanEvenIfRegistryHasMore() {
        AssetRegistry r = new AssetRegistry();
        inv(r, "minecraft:dirt", 99);
        var snap = PlanningAssetSnapshot.from(Map.of("minecraft:dirt", 5), r);
        assertEquals(5, snap.availableCounts().get("minecraft:dirt"));
    }
}
