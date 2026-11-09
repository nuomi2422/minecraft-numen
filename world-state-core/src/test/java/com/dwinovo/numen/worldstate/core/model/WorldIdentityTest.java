package com.dwinovo.numen.worldstate.core.model;

import org.junit.jupiter.api.Test;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** 世界身份：副本目录优先，避免 LevelName 仍是 live 时跨副本串观测。 */
class WorldIdentityTest {

    @Test
    void saveDirectoryWinsOverCopiedLevelName() {
        assertEquals("exp-wp-162637", WorldIdentity.of("exp-wp-162637", "live"));
    }

    @Test
    void fallsBackToLevelNameWhenSaveDirectoryMissing() {
        assertEquals("live", WorldIdentity.of(null, "live"));
        assertEquals("live", WorldIdentity.of("  ", "live"));
    }

    @Test
    void sameSaveNameUnderDifferentParentsIsDistinct() {
        String a = WorldIdentity.forPath(Path.of("saves-a/live"));
        String b = WorldIdentity.forPath(Path.of("saves-b/live"));
        assertNotEquals(a, b, "same basename must not share worldId");
        assertEquals(a, WorldIdentity.forPath(Path.of("saves-a/./live")), "normalized path is stable");
        assertNotEquals(a, WorldIdentity.forPath(Path.of("saves-a/exp-copy")));
    }

    @Test
    void unknownWhenBothMissing() {
        assertEquals("unknown", WorldIdentity.of(null, null));
        assertEquals("unknown", WorldIdentity.of("", " "));
    }
}
