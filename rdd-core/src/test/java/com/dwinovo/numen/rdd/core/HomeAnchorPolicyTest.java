package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 167 §9.14 选项 B：第一次绑定即为家，之后换床不改家。 */
class HomeAnchorPolicyTest {

    @Test
    void firstBindBecomesHome() {
        var a = HomeAnchorPolicy.decide(null, "minecraft:overworld", 371, 67, 716, 1000L);
        assertNotNull(a);
        assertEquals(371, a.x());
        assertEquals(1000L, a.setAtMillis());
    }

    @Test
    void laterBindsDoNotMoveTheHome() {
        var home = new HomeAnchorPolicy.Anchor("minecraft:overworld", 371, 67, 716, 1000L);
        var decided = HomeAnchorPolicy.decide(home, "minecraft:overworld", 358, 65, 636, 9000L);
        assertEquals(home, decided, "换床只更新复活点，家不动");
        assertTrue(HomeAnchorPolicy.respawnMovedAway(home, 358, 65, 636, 8.0));
        assertFalse(HomeAnchorPolicy.respawnMovedAway(home, 371, 67, 719, 8.0));
    }

    @Test
    void unboundRespawnMeansNoHomeYet() {
        assertNull(HomeAnchorPolicy.decide(null, null, null, null, null, 1000L));
        assertNull(HomeAnchorPolicy.decide(null, "minecraft:overworld", null, null, null, 1000L));
    }
}