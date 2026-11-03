package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 近重复门的数据入口（2026-11-03）：整库读取必须容错。
 *
 * <p>这层要是抛异常，复盘会整轮失败；所以坏行/坏文件一律跳过，返回能读到的部分。
 */
class ExperienceLibraryLookupReadAllTest {

    @Test
    void readsTitleAndMechanismAndSkipsBadLines(@TempDir Path dir) throws Exception {
        String cid = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
        Files.writeString(dir.resolve("experience-" + cid + ".jsonl"),
                "{\"title\":\"A\",\"mechanism\":\"m1\"}" + System.lineSeparator()
                        + "not-json-at-all" + System.lineSeparator()
                        + "{\"title\":\"B\"}" + System.lineSeparator(),
                StandardCharsets.UTF_8);

        var all = ExperienceLibraryLookup.readAllText(dir, cid);
        assertEquals(2, all.size(), "坏行跳过、好行保留：" + all);
        assertTrue(all.get(0).contains("A") && all.get(0).contains("m1"), all.get(0).toString());
        assertTrue(all.get(1).contains("B"), all.get(1).toString());
    }

    @Test
    void missingFileIsEmptyNotAnException(@TempDir Path dir) {
        assertTrue(ExperienceLibraryLookup.readAllText(dir, "nobody").isEmpty());
        assertTrue(ExperienceLibraryLookup.readAllText(null, "x").isEmpty());
        assertTrue(ExperienceLibraryLookup.readAllText(dir, null).isEmpty());
    }
}
