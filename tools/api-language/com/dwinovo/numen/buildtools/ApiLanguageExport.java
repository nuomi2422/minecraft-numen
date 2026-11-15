package com.dwinovo.numen.buildtools;

import com.dwinovo.numen.data.ModLanguageData;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * Build-only export of the original language catalogue, with no Minecraft or third-party runtime.
 * WHY (2026-11-16): a clean ordinary jar shipped no language JSON although all four UI keys existed.
 * USAGE: the api-loader generateApiLanguageResources task compiles only this class and the catalogue.
 * Do not copy translations here or run MC datagen from processResources: its runtime needs resources.
 */
public final class ApiLanguageExport {
    private ApiLanguageExport() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Expected one resource output directory");
        write(Path.of(args[0]));
    }

    public static void write(Path resources) throws IOException {
        Path lang = resources.resolve("assets/numen_api/lang");
        Files.createDirectories(lang);
        for (String locale : new String[]{"en_us", "zh_cn"}) {
            Files.writeString(lang.resolve(locale + ".json"), json(catalogue(locale)), StandardCharsets.UTF_8);
        }
    }

    public static Map<String, String> catalogue(String locale) {
        Map<String, String> entries = new TreeMap<>();
        ModLanguageData.addTranslations(locale, (key, value) -> {
            String previous = entries.putIfAbsent(key, value);
            if (previous != null && !previous.equals(value)) {
                throw new IllegalStateException("Conflicting translation key: " + key);
            }
        });
        return entries;
    }

    public static String json(Map<String, String> entries) {
        StringBuilder out = new StringBuilder("{\n");
        boolean first = true;
        for (var entry : new TreeMap<>(entries).entrySet()) {
            if (!first) out.append(",\n");
            first = false;
            out.append("  ").append(quote(entry.getKey())).append(": ").append(quote(entry.getValue()));
        }
        return out.append("\n}\n").toString();
    }

    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
                    else out.append(ch);
                }
            }
        }
        return out.append('"').toString();
    }
}
