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
    public static synchronized boolean enqueue(Path configDir, String ref, String version, String useId) {
        if (configDir == null || ref == null || ref.isBlank()) return false;
        String key = ref + "\u0000" + (version == null ? "" : version) + "\u0000" + (useId == null ? "" : useId);
        if (!SEEN.add(key)) return false;
        try {
            Files.createDirectories(configDir);
            Path f = configDir.resolve(FILE);
            String json = "{\"ref\":\"" + esc(ref) + "\",\"version\":\"" + esc(version == null ? "" : version)
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
