package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.api.Observation;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07：地点目录只列同维度新鲜观测；超限省略；不猜距离/当前位置。 */
class RddAssetLocationCatalogTest {

    private static AssetRegistry reg(String assetId, String label, String dim, long at) {
        AssetRegistry r = new AssetRegistry();
        var obs = new Observation("obs-" + assetId, "world_base", "test", dim, at,
                Map.of("kind", "base", "label", label, "dimension", dim, "x", 10, "y", 64, "z", -20));
        r.apply(obs, assetId, AssetScope.GLOBAL, null);
        return r;
    }

    @Test void freshSameDimensionVisible() {
        AssetRegistry r = reg("base|a", "个人重生基地", "minecraft:overworld", 1000L);
        String out = RddAssetContext.locationCatalog(r, "minecraft:overworld", 2000L, 60_000L, 3, 200);
        assertTrue(out.contains("个人重生基地"), out);
        assertTrue(out.contains("(10,64,-20)"), out);
        assertTrue(out.contains("观测于 1000"), out);
    }

    @Test void wrongDimensionAndStaleAndFutureHidden() {
        AssetRegistry r = reg("base|a", "下界基地", "minecraft:the_nether", 1000L);
        assertEquals("", RddAssetContext.locationCatalog(r, "minecraft:overworld", 2000L, 60_000L, 3, 200));
        AssetRegistry stale = reg("base|b", "旧基地", "minecraft:overworld", 1000L);
        assertEquals("", RddAssetContext.locationCatalog(stale, "minecraft:overworld", 100_000L, 60_000L, 3, 200),
                "过期不显示，也不冒充当前");
        AssetRegistry future = reg("base|c", "未来基地", "minecraft:overworld", 5000L);
        assertEquals("", RddAssetContext.locationCatalog(future, "minecraft:overworld", 1000L, 60_000L, 3, 200));
    }

    @Test void limitAndCharBudgetOmitExplicitly() {
        AssetRegistry r = new AssetRegistry();
        for (int i = 0; i < 5; i++) {
            var obs = new Observation("obs-" + i, "world_base", "test", "minecraft:overworld", 1000L,
                    Map.of("kind", "base", "label", "基地" + i, "dimension", "minecraft:overworld", "x", i, "y", 64, "z", 0));
            r.apply(obs, "base|" + i, AssetScope.GLOBAL, null);
        }
        String two = RddAssetContext.locationCatalog(r, "minecraft:overworld", 2000L, 60_000L, 2, 10_000);
        assertTrue(two.contains("省略 3 项"), two);
        String tiny = RddAssetContext.locationCatalog(r, "minecraft:overworld", 2000L, 60_000L, 5, 5);
        assertTrue(tiny.isEmpty() || tiny.length() <= 5 + 20, tiny);
    }
}
