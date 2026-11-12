package com.dwinovo.numen.plugins.camera.possess;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Source seam contracts: the real MC shell must consume the periodic refresh decision.
 * Why source rather than shadow Minecraft classes: fake players cannot prove packet dispatch.
 * 2026-11-12 counterexample: vanilla spectate has already absMoveTo'd the observer, so
 * distance=0 must NOT suppress an otherwise due position packet. These tests do not
 * claim receipt, chunk meshes, or rendering success; the parent runs them test-only.
 */
class PossessionRefreshWiringTest {
    private static String source(String relative) throws IOException {
        Path module = Path.of("src/main/java/com/dwinovo/numen/plugins/camera");
        if (!Files.isDirectory(module)) {
            module = Path.of("plugins/camera").resolve(module);
        }
        return Files.readString(module.resolve(relative));
    }

    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, signature);
        int open = source.indexOf('{', start);
        int depth = 1, end = open + 1;
        while (depth > 0 && end < source.length()) {
            char c = source.charAt(end++);
            if (c == '{') depth++;
            if (c == '}') depth--;
        }
        assertEquals(0, depth);
        return source.substring(open + 1, end - 1);
    }

    @Test void dueRefreshDoesNotDependOnObserverDistance() throws IOException {
        String follow = method(source("possess/Possession.java"), "private static void rebindAndFollow()");
        assertTrue(follow.contains("rebindCooldown = 20;"), "keep existing ~1s cadence");
        assertTrue(follow.contains("self.connection.teleport("), "vanilla position packet path");
        assertFalse(follow.contains("distanceTo("), "spectate body position is not packet evidence");
    }

    @Test void queuedWorkUsesCapturedBindingAndUuidBeforeMutatingObserver() throws IOException {
        String follow = method(source("possess/Possession.java"), "private static void rebindAndFollow()");
        assertTrue(follow.indexOf("UUID expectedTarget = targetUuid;") < follow.indexOf("server.execute("));
        assertTrue(follow.indexOf("long scheduledVersion = bindingVersion.get();") < follow.indexOf("server.execute("));
        assertTrue(follow.contains("getPlayer(expectedTarget)"));
        assertFalse(follow.contains("getPlayerByName"), "known UUID must not switch to a same-name player");
        int decision = follow.indexOf("FollowRefresh.mayRefresh(");
        assertTrue(decision >= 0, "production must consume pure safety decision");
        assertTrue(decision < follow.indexOf("self.setCamera(target)"));
        assertTrue(decision < follow.indexOf("self.connection.teleport("));
        assertTrue(follow.contains("target.isAlive()"));
        assertTrue(follow.contains("self.gameMode.getGameModeForPlayer() == GameType.SPECTATOR"));
    }

    @Test void crossDimensionMovesOnlyObserverThenRebinds() throws IOException {
        String follow = method(source("possess/Possession.java"), "private static void rebindAndFollow()");
        int dimension = follow.indexOf("if (self.serverLevel() != target.serverLevel())");
        int move = follow.indexOf("self.teleportTo(target.serverLevel()");
        int camera = follow.indexOf("self.setCamera(target)");
        assertTrue(dimension >= 0 && dimension < move && move < camera, "cross-dimension API first; it resets camera");
        assertFalse(follow.contains("target.teleport"));
        assertFalse(follow.contains("target.setCamera"));
    }

    @Test void releaseInvalidatesQueuedWorkBeforeServerExecution() throws IOException {
        String release = method(source("possess/Possession.java"), "public static String release()");
        assertTrue(release.indexOf("bindingVersion.incrementAndGet();") >= 0);
        assertTrue(release.indexOf("bindingVersion.incrementAndGet();") < release.indexOf("server.execute("));
        String deactivate = method(source("possess/Possession.java"), "private static void deactivate()");
        assertTrue(deactivate.contains("targetUuid = null;"));
        assertTrue(deactivate.contains("bindingVersion.incrementAndGet();"));
    }

    @Test void stateSeparatesPossessionFromRigAndTetherWithoutChangingOldFields() throws IOException {
        String state = method(source("api/DirectorServer.java"), "private static JsonObject stateJson()");
        assertTrue(state.contains("o.addProperty(\"mode\", CameraRig.mode().name())"));
        assertTrue(state.contains("follow.addProperty(\"on\", CompanionTether.follow())"));
        assertTrue(state.contains("possession.addProperty(\"active\", Possession.isActive())"));
        assertTrue(state.contains("possession.addProperty(\"target\", Possession.targetName())"));
        assertTrue(state.contains("possession.addProperty(\"targetUuid\", Possession.targetUuid())"));
        assertTrue(state.contains("possession.addProperty(\"cameraMatchesTarget\""));
        assertTrue(state.contains("possession.addProperty(\"positionRefreshRequests\""));
        assertTrue(state.contains("o.add(\"possession\", possession)"));
    }
}
