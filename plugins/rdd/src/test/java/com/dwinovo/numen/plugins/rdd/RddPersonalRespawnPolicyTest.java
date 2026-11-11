package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Personal ground consumer regression, 2026-11-11.
 * Run RddPersonalRespawnPolicyTest through the parent's managed test selector.
 * Pure truth table tests the route; source pins test the Minecraft shell without a game.
 * This is not runtime evidence: safe standing itself remains vanilla's responsibility.
 */
class RddPersonalRespawnPolicyTest {
    @Test void onlyExplicitForcedGroundUsesNewRoute() {
        for (boolean position : new boolean[]{false, true}) {
            for (boolean forced : new boolean[]{false, true}) {
                for (boolean bed : new boolean[]{false, true}) {
                    assertEquals(position && forced && !bed,
                            RddPersonalRespawnPolicy.useGround(position, forced, bed));
                }
            }
        }
    }

    @Test void laterBedOverridesGroundWithoutAnyNewStoredBinding() {
        assertTrue(RddPersonalRespawnPolicy.useGround(true, true, false));
        assertFalse(RddPersonalRespawnPolicy.useGround(true, false, true));
        assertFalse(RddPersonalRespawnPolicy.useGround(true, true, true));
        assertFalse(RddPersonalRespawnPolicy.useGround(false, false, false));
    }

    private static String source() throws Exception {
        for (Path dir = Path.of("").toAbsolutePath(); dir != null; dir = dir.getParent()) {
            for (String relative : new String[]{"src/main/java", "plugins/rdd/src/main/java"}) {
                Path file = dir.resolve(relative + "/com/dwinovo/numen/plugins/rdd/RddPlugin.java");
                if (Files.isRegularFile(file)) return Files.readString(file);
            }
        }
        throw new AssertionError("RddPlugin source not found");
    }

    @Test void spawnConsumesPersonalBeforeCachedBedFallback() throws Exception {
        String src = source();
        int spawn = src.indexOf("numen.on(CompanionEvent.SPAWN");
        int take = src.indexOf("BED_RESPAWN_PREFERENCE.remove(uuid)", spawn);
        int personal = src.indexOf("if (applyPersonalGroundRespawn(body)) return;", take);
        int bed = src.indexOf("BlockPos bedPos =", take);
        assertTrue(spawn >= 0 && take > spawn && personal > take && bed > personal);
    }

    @Test void shellUsesPersonalDimensionAndVanillaValidationBeforeMoving() throws Exception {
        String src = source();
        int start = src.indexOf("private static boolean applyPersonalGroundRespawn(");
        String shell = src.substring(start, src.indexOf("private static Vec3 bedStandPos", start));
        assertTrue(shell.contains("body.getRespawnPosition()"));
        assertTrue(shell.contains("getLevel(body.getRespawnDimension())"));
        assertTrue(shell.contains("RddPersonalRespawnPolicy.useGround("));
        assertTrue(shell.contains("findRespawnPositionAndUseSpawnBlock(true,"));
        assertTrue(shell.contains("transition.missingRespawnBlock() || transition.newLevel() != target"));
        assertTrue(shell.contains("DismountHelper.findSafeDismountLocation("));
        assertTrue(shell.contains("body.getType(), target, personal, true"));
        int safety = shell.indexOf("if (stand == null) return true;");
        assertTrue(safety >= 0 && safety < shell.indexOf("body.moveTo("));
        assertTrue(safety < shell.indexOf("body.teleportTo(target,"));
        assertFalse(shell.contains("BlockPos.ZERO"), "origin is a valid explicit coordinate");
        assertFalse(shell.contains("getSharedSpawnPos"));
        assertFalse(shell.contains("setRespawnPosition"), "consumer must not rewrite vanilla binding");
        assertFalse(shell.contains("new Vec3"), "no unsafe raw coordinate fallback");
    }
}
