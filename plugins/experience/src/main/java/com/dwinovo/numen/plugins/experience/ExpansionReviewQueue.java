package com.dwinovo.numen.plugins.experience;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;

/** 2026-11-08 C1: bounded review queue for successful experience_expand deliveries.
 *  Idempotent per (ref, version, useId); only records a request, worker is separate. */
public final class ExpansionReviewQueue {
    static final String FILE = "expansion-review-queue.jsonl";
    private static final Set<String> SEEN = new LinkedHashSet<>();

    private ExpansionReviewQueue() {}

    /** @return true when a new line was appended; false for duplicate/IO failure. */
    public static synchronized boolean enqueue(Path configDir, java.util.UUID companion, String ref, String version, String useId) {
        if (configDir == null || ref == null || ref.isBlank()) return false;
        String key = String.valueOf(companion) + "\u0000" + ref + "\u0000" + (version == null ? "" : version) + "\u0000" + (useId == null ? "" : useId);
        if (!SEEN.add(key)) return false;
        try {
            Files.createDirectories(configDir);
            Path f = configDir.resolve(FILE);
            String json = "{\"companion\":\"" + esc(String.valueOf(companion)) + "\",\"ref\":\"" + esc(ref) + "\",\"version\":\"" + esc(version == null ? "" : version)
                    + "\",\"use_id\":\"" + esc(useId == null ? "" : useId)
                    + "\",\"at\":" + System.currentTimeMillis() + "}";
            Files.writeString(f, json + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            return true;
        } catch (IOException e) {
            SEEN.remove(key);
            return false;
        }
    }

    /** 2026-11-08 C1 worker: take up to max entries, leaving the rest in place. */
    public static synchronized java.util.List<Entry> take(Path configDir, int max) {
        java.util.List<Entry> out = new java.util.ArrayList<>();
        if (configDir == null || max <= 0) return out;
        Path f = configDir.resolve(FILE);
        if (!Files.exists(f)) return out;
        try {
            java.util.List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            java.util.List<String> rest = new java.util.ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) continue;
                if (out.size() < max) {
                    Entry e = parse(line);
                    if (e != null) { out.add(e); continue; }
                }
                rest.add(line);
            }
            Path tmp = f.resolveSibling(FILE + ".tmp");
            Files.writeString(tmp, rest.isEmpty() ? "" : String.join(System.lineSeparator(), rest) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return out;
        } catch (IOException | RuntimeException e) {
            return java.util.List.of();
        }
    }

    public record Entry(String companion, String ref, String version, String useId) {}

    private static Entry parse(String line) {
        try {
            var o = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            return new Entry(o.get("companion").getAsString(), o.get("ref").getAsString(),
                    o.get("version").getAsString(), o.get("use_id").getAsString());
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static synchronized int size(Path configDir) {
        try {
            Path f = configDir.resolve(FILE);
            return Files.exists(f) ? Files.readAllLines(f, StandardCharsets.UTF_8).size() : 0;
        } catch (IOException e) {
            return -1;
        }
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
