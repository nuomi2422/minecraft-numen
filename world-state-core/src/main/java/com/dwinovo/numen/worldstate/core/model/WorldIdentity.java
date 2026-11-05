package com.dwinovo.numen.worldstate.core.model;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 世界存档身份：本地宿主使用 {@link #forPath(Path)}；旧 {@link #of(String, String)} 保持原语义。
 *
 * <p>为什么不是直接用 LevelName：复制存档做实验时，副本目录叫 `exp-xxx`，但
 * `level.dat` 里的 LevelName 仍是 `live`。如果观测键按 LevelName 分命名空间，
 * 两个副本会共用同一个 worldId，A/B 对照就会串数据。
 *
 * <p>纯函数、无 MC 依赖：可在 world-state-core 离线单测。
 */
public final class WorldIdentity {

    private WorldIdentity() {
    }

    /**
     * 2026-11-05：本地存档用规范化绝对路径的 SHA-256 摘要 + 原目录名；不读取文件系统。
     * 例如 a/saves/live 与 b/saves/live 的 basename 都是 live，只取名字（或 LevelName）会串域，
     * String.hashCode 的短整数也不适合稳定标识。保留 16 位十六进制摘要，不泄露完整宿主路径。
     * 路径缺失时明确抛错，由宿主绑定隔离的 unknown 会话；不能统一返回共享的 unknown 世界。
     * 不用 toRealPath：观测身份计算不应访问磁盘或依赖目录当时是否可读。
     */
    public static String forPath(Path saveRoot) {
        if (saveRoot == null || saveRoot.toString().isBlank()) {
            throw new IllegalArgumentException("missing save root path");
        }
        Path normalized = saveRoot.toAbsolutePath().normalize();
        Path name = normalized.getFileName();
        String basename = name == null ? "world" : name.toString();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.toString().getBytes(StandardCharsets.UTF_8));
            return basename + "-" + HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable for world identity", e);
        }
    }

    /** 旧身份函数保持兼容：存档目录名优先；空白/缺失回退显示名；两者都不可用才 `unknown`。 */
    public static String of(String saveDirName, String levelName) {
        if (saveDirName != null && !saveDirName.isBlank()) {
            return saveDirName.trim();
        }
        if (levelName != null && !levelName.isBlank()) {
            return levelName.trim();
        }
        return "unknown";
    }
}
