package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 播种纯规则（2026-11-06）：种子→作物、优先级、认不出不猜。 */
class SowRulesTest {

    @Test
    void mapsEachSeedToItsCrop() {
        assertEquals("minecraft:wheat", SowRules.cropBlockIdForSeed("minecraft:wheat_seeds"));
        assertEquals("minecraft:carrots", SowRules.cropBlockIdForSeed("minecraft:carrot"));
        assertEquals("minecraft:potatoes", SowRules.cropBlockIdForSeed("minecraft:potato"));
        assertEquals("minecraft:beetroots", SowRules.cropBlockIdForSeed("minecraft:beetroot_seeds"));
    }

    @Test
    void unknownSeedIsNullNotWheat() {
        assertNull(SowRules.cropBlockIdForSeed("minecraft:melon_seeds"));
        assertNull(SowRules.cropBlockIdForSeed(null));
    }

    @Test
    void picksFirstAvailableByPriority() {
        assertEquals("minecraft:wheat_seeds",
                SowRules.pickSeedId(Map.of("minecraft:wheat_seeds", 20, "minecraft:carrot", 3)));
        assertEquals("minecraft:potato",
                SowRules.pickSeedId(Map.of("minecraft:wheat_seeds", 0, "minecraft:potato", 5)));
    }

    @Test
    void noSeedReturnsNull() {
        assertNull(SowRules.pickSeedId(Map.of()));
        assertNull(SowRules.pickSeedId(Map.of("minecraft:melon_seeds", 9)));
    }
}
