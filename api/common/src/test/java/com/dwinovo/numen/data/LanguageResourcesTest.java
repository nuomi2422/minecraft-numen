package com.dwinovo.numen.data;

import com.dwinovo.numen.buildtools.ApiLanguageExport;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Ordinary packaging must emit the existing catalogue, not depend on untracked MC datagen files. */
class LanguageResourcesTest {
    @Test void exportsBothCompleteCataloguesAsDeterministicUtf8(@TempDir Path dir) throws Exception {
        ApiLanguageExport.write(dir);
        for (String locale : new String[]{"en_us", "zh_cn"}) {
            Path file = dir.resolve("assets/numen_api/lang/" + locale + ".json");
            byte[] first = Files.readAllBytes(file);
            JsonObject actual = JsonParser.parseString(new String(first, StandardCharsets.UTF_8)).getAsJsonObject();
            Map<String, String> expected = new TreeMap<>();
            ModLanguageData.addTranslations(locale, expected::put);
            assertFalse(expected.isEmpty());
            assertEquals(expected.keySet(), actual.keySet());
            expected.forEach((key, value) -> assertEquals(value, actual.get(key).getAsString(), locale + ": " + key));
            ApiLanguageExport.write(dir);
            assertArrayEquals(first, Files.readAllBytes(file), "same catalogue must emit byte-identical resources");
        }
        JsonObject en = JsonParser.parseString(Files.readString(dir.resolve("assets/numen_api/lang/en_us.json"))).getAsJsonObject();
        JsonObject zh = JsonParser.parseString(Files.readString(dir.resolve("assets/numen_api/lang/zh_cn.json"))).getAsJsonObject();
        assertEquals("Chat", en.get("numen.tab.chat").getAsString());
        assertEquals("Status", en.get("numen.tab.status").getAsString());
        assertEquals("Settings", en.get("numen.tab.settings").getAsString());
        assertEquals("PLAN", en.get("numen.chat.plan").getAsString());
        assertEquals("对话", zh.get("numen.tab.chat").getAsString());
        assertEquals("状态", zh.get("numen.tab.status").getAsString());
        assertEquals("设置", zh.get("numen.tab.settings").getAsString());
        assertEquals("计划", zh.get("numen.chat.plan").getAsString());
    }

    @Test void ordinaryResourceInputsContainBothGeneratedLocales() throws Exception {
        for (String locale : new String[]{"en_us", "zh_cn"}) {
            String path = "/assets/numen_api/lang/" + locale + ".json";
            try (var stream = getClass().getResourceAsStream(path)) {
                assertNotNull(stream, "ordinary processResources input missing: " + path);
                JsonObject resource = JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
                Map<String, String> expected = ApiLanguageExport.catalogue(locale);
                assertEquals(expected.keySet(), resource.keySet());
                expected.forEach((key, value) -> assertEquals(value, resource.get(key).getAsString(), path + ": " + key));
            }
        }
    }

    @Test void exporterEscapesJsonWithoutChangingTranslationText() {
        String key = "quoted\" key\\\n";
        String value = "中文\"\\\b\f\n\r\t" + (char) 1;
        String json = ApiLanguageExport.json(Map.of(key, value, "a", "first"));
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        assertEquals(value, parsed.get(key).getAsString());
        assertEquals("first", parsed.get("a").getAsString());
        assertTrue(json.indexOf("\"a\"") < json.indexOf("quoted"), "stable key order");
        assertFalse(json.contains(String.valueOf((char) 1)), "raw control characters are not legal JSON");
    }
}
