package com.dwinovo.numen.rdd.policy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 167 §B2：村庄候选排序要稳定、可解释。 */
class VillageRankTest {

    private static VillageRank.Candidate c(String id, int villagers, int chests, int crops, int golems) {
        return new VillageRank.Candidate(id, "minecraft:overworld", 0, 64, 0,
                Map.of("villagers", villagers, "chests", chests, "crops", crops, "iron_golem", golems), 0L);
    }

    @Test
    void villagersAndChestsDominate() {
        var rich = c("rich", 4, 2, 0, 0);
        var empty = c("empty", 0, 0, 40, 0);
        assertEquals(List.of("rich", "empty"),
                VillageRank.order(List.of(empty, rich)).stream().map(VillageRank.Candidate::id).toList());
    }

    @Test
    void tiesBreakByIdSoOrderIsReproducible() {
        var a = c("aaa", 1, 0, 0, 0);
        var b = c("bbb", 1, 0, 0, 0);
        assertEquals(List.of("aaa", "bbb"),
                VillageRank.order(List.of(b, a)).stream().map(VillageRank.Candidate::id).toList());
    }

    @Test
    void nullsAreTolerated() {
        assertNull(VillageRank.best(null));
        assertNull(VillageRank.best(List.of()));
        assertEquals(0, VillageRank.score(null));
        assertEquals(0, VillageRank.score(new VillageRank.Candidate("x", "d", 0, 0, 0, null, 0L)));
    }
}
