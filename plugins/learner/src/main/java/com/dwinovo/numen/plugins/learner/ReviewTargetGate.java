package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.Memo;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/** 2026-11-08 C1: re-check expansion target immediately before committing review results.
 *  Fail closed: missing/retracted -> STALE; version not verifiable in current schema -> VERSION_UNKNOWN. */
public final class ReviewTargetGate {
    private ReviewTargetGate() {}

    public record Check(boolean ok, String reason) {}

    public static Check check(Path configDir, UUID companion, Memo memo) {
        if (memo == null || !"EXPERIENCE_EXPAND".equals(memo.stage())) return new Check(true, "");
        String snap = memo.snapshot() == null ? "" : memo.snapshot();
        String ref;
        String version;
        if (snap.trim().startsWith("{")) {
            try {
                JsonObject o = JsonParser.parseString(snap).getAsJsonObject();
                ref = o.has("ref") ? o.get("ref").getAsString() : null;
                version = o.has("version") ? o.get("version").getAsString() : null;
            } catch (RuntimeException e) {
                return new Check(false, "BAD_SNAPSHOT");
            }
        } else {
            ref = field(snap, "ref");
            version = field(snap, "version");
        }
        if (ref == null || !ref.startsWith("exp:")) return new Check(false, "BAD_REF");
        if (version == null || version.isBlank()) return new Check(false, "VERSION_UNKNOWN");
        Path f = configDir.resolve("experience-" + companion + ".jsonl");
        if (!Files.isRegularFile(f)) return new Check(false, "EXPERIENCE_FILE_MISSING");
        String id = ref.substring(4);
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line == null || !line.contains("\"id\":\"" + id + "\"")) continue;
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                if (o.has("retracted") && o.get("retracted").getAsBoolean()) return new Check(false, "RETRACTED");
                String pub = o.has("publication") && !o.get("publication").isJsonNull()
                        ? o.get("publication").getAsString() : "";
                if ("REVOKED".equalsIgnoreCase(pub)) return new Check(false, "REVOKED");
                String current = o.has("content_fingerprint") && !o.get("content_fingerprint").isJsonNull()
                        ? o.get("content_fingerprint").getAsString() : "";
                if (current.isBlank()) return new Check(false, "VERSION_UNVERIFIED");
                if (!current.equals(version)) return new Check(false, "VERSION_MISMATCH");
                return new Check(true, "OK");
            }
            return new Check(false, "MISSING");
        } catch (IOException | RuntimeException e) {
            return new Check(false, "READ_FAILED");
        }
    }

    private static String field(String text, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile(key + "=([^\\s]+)").matcher(text);
        return m.find() ? m.group(1) : null;
    }
}
