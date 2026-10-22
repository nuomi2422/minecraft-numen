package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** G6b：只读 hits 适配器（读经验库 jsonl，算「同主题」命中）。 */
class ExperienceLibraryLookupTest {

    private static final String UUID = "e44fe5f8-4ab6-4478-9d72-974393ea4815";

    private static void write(Path dir, String... lines) throws Exception {
        Files.writeString(dir.resolve("experience-" + UUID + ".jsonl"),
                String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    @Test
    void sameTitleIsHighSimilarity(@TempDir Path dir) throws Exception {
        write(dir, "{\"id\":\"a\",\"title\":\"白天不要调 sleep\",\"trigger_strings\":[\"睡觉\"]}");
        List<RetrievalDecision.Hit> hits = ExperienceLibraryLookup.lookup(
                dir, UUID, "白天不要调 sleep", List.of("过夜"), List.of());
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).highSimilarity(), "同标题 = 同一件事");
        assertEquals("exp:a", hits.get(0).entryRef());
        assertTrue(hits.get(0).why().contains("同标题"));
    }

    @Test
    void sharedTriggerIsHighEvenWhenTitleDiffers(@TempDir Path dir) throws Exception {
        write(dir, "{\"id\":\"b\",\"title\":\"睡觉前先找床\",\"trigger_strings\":[\"睡觉\",\"床\"]}");
        List<RetrievalDecision.Hit> hits = ExperienceLibraryLookup.lookup(
                dir, UUID, "天黑之前要躺下", List.of("睡觉"), List.of());
        assertEquals(1, hits.size());
        assertTrue(hits.get(0).highSimilarity(), "同触发词也算同一件事");
        assertTrue(hits.get(0).why().contains("睡觉"));
    }

    @Test
    void tagOverlapIsOnlyWeak(@TempDir Path dir) throws Exception {
        write(dir, "{\"id\":\"c\",\"title\":\"合成木镐\",\"trigger_strings\":[\"木镐\"],\"tags\":[\"挖矿\"]}");
        List<RetrievalDecision.Hit> hits = ExperienceLibraryLookup.lookup(
                dir, UUID, "挖铁矿", List.of("铁矿"), List.of("挖矿"));
        assertEquals(1, hits.size());
        assertFalse(hits.get(0).highSimilarity(), "只有 tag 相交 = 相关但不是同一件事");
    }

    @Test
    void unrelatedEntriesAreNotReturned(@TempDir Path dir) throws Exception {
        write(dir, "{\"id\":\"d\",\"title\":\"喂鸡\",\"trigger_strings\":[\"鸡\"]}");
        assertTrue(ExperienceLibraryLookup.lookup(dir, UUID, "挖铁矿", List.of("铁矿"), List.of()).isEmpty());
    }

    @Test
    void missingLibraryIsEmptyNotFailure(@TempDir Path dir) {
        assertTrue(ExperienceLibraryLookup.lookup(dir, UUID, "任意", List.of("x"), List.of()).isEmpty());
    }

    @Test
    void brokenLineIsSkipped(@TempDir Path dir) throws Exception {
        write(dir,
                "{ this is not json",
                "{\"id\":\"e\",\"title\":\"喂鸡\",\"trigger_strings\":[\"鸡\"]}");
        List<RetrievalDecision.Hit> hits = ExperienceLibraryLookup.lookup(
                dir, UUID, "喂鸡", List.of(), List.of());
        assertEquals(1, hits.size(), "一条坏行不该让整次查询失败");
    }

    @Test
    void textLookupFindsByTriggerInsideTheText(@TempDir Path dir) throws Exception {
        write(dir, "{\"id\":\"f\",\"title\":\"白天别睡\",\"trigger_strings\":[\"睡觉\"],\"tags\":[\"作息\"]}");
        java.util.List<RetrievalDecision.Hit> hits = ExperienceLibraryLookup.lookupByText(
                dir, UUID, "今天天亮了，主人让我去睡觉，结果被拒了");
        assertEquals(1, hits.size(), "文本里出现了触发词「睡觉」就该命中");
        assertTrue(hits.get(0).highSimilarity());
    }

    @Test
    void textLookupFindsByTitleInsideTheText(@TempDir Path dir) throws Exception {
        write(dir, "{\"id\":\"g\",\"title\":\"白天别睡\"}");
        assertEquals(1, ExperienceLibraryLookup.lookupByText(dir, UUID, "复盘：白天别睡 这条要改").size());
    }

    @Test
    void textLookupReturnsNothingWhenTextIsUnrelated(@TempDir Path dir) throws Exception {
        write(dir, "{\"id\":\"h\",\"title\":\"白天别睡\",\"trigger_strings\":[\"睡觉\"]}");
        assertTrue(ExperienceLibraryLookup.lookupByText(dir, UUID, "修好了铁镐").isEmpty());
    }

    @Test
    void hitsAreCapped(@TempDir Path dir) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append("{\"id\":\"x").append(i).append("\",\"title\":\"同题\",\"trigger_strings\":[\"t\"]}\n");
        }
        Files.writeString(dir.resolve("experience-" + UUID + ".jsonl"), sb.toString(), StandardCharsets.UTF_8);
        assertEquals(ExperienceLibraryLookup.MAX_HITS,
                ExperienceLibraryLookup.lookup(dir, UUID, "同题", List.of("t"), List.of()).size());
    }
}
