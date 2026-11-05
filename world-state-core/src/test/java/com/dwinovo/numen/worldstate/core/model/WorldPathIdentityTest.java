package com.dwinovo.numen.worldstate.core.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-05：本地路径身份的纯函数回归，不要求真实存档或启动游戏。
 * 案例：a/saves/live 和 b/saves/live 同名，旧 basename 方案会让两世界共用观测。
 * 不用创建目录/读取 level.dat 的简单替身：身份应只取规范化绝对路径，不依赖文件系统状态。
 * 运行 :world-state-core:test --tests '*WorldPathIdentityTest'；旧 of() 的测试不改。
 */
class WorldPathIdentityTest {
    @Test
    void sameBasenameAtDifferentPathsHasDifferentIdentity() {
        String first = WorldIdentity.forPath(Path.of("identity-fixture", "a", "saves", "live"));
        String second = WorldIdentity.forPath(Path.of("identity-fixture", "b", "saves", "live"));
        assertNotEquals(first, second);
        assertTrue(first.matches("live-[0-9a-f]{16}"));
        assertTrue(second.matches("live-[0-9a-f]{16}"));
    }

    @Test
    void dotAndParentSegmentsNormalizeToSameIdentity() {
        Path direct = Path.of("identity-fixture", "saves", "live");
        Path indirect = Path.of("identity-fixture", "saves", ".", "other", "..", "live", ".");
        assertEquals(WorldIdentity.forPath(direct), WorldIdentity.forPath(indirect));
    }

    @Test
    void relativeAndAbsoluteRepresentationsHaveSameIdentity() {
        Path relative = Path.of("identity-fixture", "saves", "live");
        assertEquals(WorldIdentity.forPath(relative), WorldIdentity.forPath(relative.toAbsolutePath()));
        assertEquals(WorldIdentity.forPath(relative), WorldIdentity.forPath(relative));
    }

    @Test
    void usesUtf8Sha256PrefixAndPreservesBasename() throws Exception {
        Path root = Path.of("identity-fixture", "世界", "测试存档").toAbsolutePath().normalize();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(root.toString().getBytes(StandardCharsets.UTF_8));
        String expected = "测试存档-" + HexFormat.of().formatHex(hash, 0, 8);
        assertEquals(expected, WorldIdentity.forPath(root));
        assertFalse(WorldIdentity.forPath(root).contains(root.getParent().toString()));
    }

    @Test
    void missingPathFailsRatherThanCreateSharedUnknownWorld() {
        assertThrows(IllegalArgumentException.class, () -> WorldIdentity.forPath(null));
        assertThrows(IllegalArgumentException.class, () -> WorldIdentity.forPath(Path.of("")));
        assertThrows(IllegalArgumentException.class, () -> WorldIdentity.forPath(Path.of(" ")));
    }

    @Test
    void filesystemRootGetsHashEvenWithoutBasename() {
        String identity = WorldIdentity.forPath(Path.of("").toAbsolutePath().getRoot());
        assertTrue(identity.matches("world-[0-9a-f]{16}"));
    }
}
