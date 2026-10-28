package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 83 号 §4「单一检索入口」的开关面证据（2026-10-28 补）。
 *
 * <p>「一个口」的判据依赖这个开关：只有显式 unified 才走统一检索（桥）；
 * 一切读不到/读坏/不认识的情况都退 legacy（旧行为零变化）。这些分支此前
 * 没有任何测试钉着——开关回退错了，双通道（同一条经验注入两遍）就回来了。
 */
class RetrievalPolicySingleEntryTest {

    @Test
    void unifiedOnlyWhenConfigSaysSo(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(RetrievalPolicy.FILE_NAME);
        Files.writeString(f, "{\"experienceRetrievalMode\":\"unified\"}");
        assertEquals(RetrievalPolicy.Mode.UNIFIED, RetrievalPolicy.mode(f), "显式 unified");

        Files.writeString(f, "{\"experienceRetrievalMode\":\"legacy\"}");
        assertEquals(RetrievalPolicy.Mode.LEGACY, RetrievalPolicy.mode(f), "显式 legacy");

        Files.writeString(f, "{\"experienceRetrievalMode\":\"星际穿越\"}");
        assertEquals(RetrievalPolicy.Mode.LEGACY, RetrievalPolicy.mode(f), "不认识的值退 legacy，不猜");

        Files.delete(f);
        assertEquals(RetrievalPolicy.Mode.LEGACY, RetrievalPolicy.mode(f), "缺文件退 legacy");
    }

    @Test
    void badJsonFallsBackToLegacy(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(RetrievalPolicy.FILE_NAME);
        Files.writeString(f, "{not json at all");
        assertEquals(RetrievalPolicy.Mode.LEGACY, RetrievalPolicy.mode(f), "坏 JSON 退 legacy");

        Files.writeString(f, "[1,2,3]");
        assertEquals(RetrievalPolicy.Mode.LEGACY, RetrievalPolicy.mode(f), "非对象退 legacy");

        Files.writeString(f, "{}");
        assertEquals(RetrievalPolicy.Mode.LEGACY, RetrievalPolicy.mode(f), "缺键退 legacy");
    }

    @Test
    void hotReloadPicksUpChange(@TempDir Path dir) throws Exception {
        Path f = dir.resolve(RetrievalPolicy.FILE_NAME);
        Files.writeString(f, "{\"experienceRetrievalMode\":\"legacy\"}");
        assertEquals(RetrievalPolicy.Mode.LEGACY, RetrievalPolicy.mode(f));
        Files.writeString(f, "{\"experienceRetrievalMode\":\"unified\"}");
        assertEquals(RetrievalPolicy.Mode.UNIFIED, RetrievalPolicy.mode(f), "改文件即热切换，不重启");
        assertEquals(RetrievalPolicy.Mode.UNIFIED, RetrievalPolicy.mode(f), "重复读稳定（缓存）");
    }
}
