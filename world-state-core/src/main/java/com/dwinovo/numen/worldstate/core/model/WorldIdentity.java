package com.dwinovo.numen.worldstate.core.model;

/**
 * 世界存档身份：优先用<b>存档目录名</b>，其次才回退到 level.dat 的显示名。
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

    /** 存档目录名优先；空白/缺失回退显示名；两者都不可用才 `unknown`。 */
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
