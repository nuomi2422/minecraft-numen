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

    public record Check(boolean ok, String reason) {
        /** 2026-11-11 C1: only definite invalidation terminates an old expansion memo.
         * Missing/IO/unknown version remain retryable; no new inference from absence. */
        public boolean terminal() {
            return !ok && java.util.Set.of("VERSION_MISMATCH", "RETRACTED", "REVOKED").contains(reason);
        }
    }

    /**
     * 2026-11-13: the same server object can change identity while a skill review is in flight.
     * Gate this review's start/commit tokens, never the durable historical sample's scope:
     * comparing an old sample to the new server would make restored C0 memos retry forever.
     * These checks do not authenticate a ledger fact, judge validity, or alter the old expansion gate.
     */
    public static Check check(Path configDir, UUID companion, Memo memo,
                              com.dwinovo.numen.api.WorldContextStamp.Token reviewStart,
                              com.dwinovo.numen.api.WorldContextStamp.Token current) {
        if (!skillReview(memo)) return check(configDir, companion, memo);
        if (companion == null || !complete(reviewStart) || !complete(current))
            return new Check(false, "REVIEW_CONTEXT_UNKNOWN");
        if (!reviewStart.equals(current)) return new Check(false, "REVIEW_CONTEXT_CHANGED");
        return new Check(true, "OK");
    }

    private static boolean skillReview(Memo memo) {
        return memo != null && ("skill_delivery".equals(memo.stage()) || "skill_task_result".equals(memo.stage()));
    }

    private static boolean complete(com.dwinovo.numen.api.WorldContextStamp.Token token) {
        return token != null && token.epoch() > 0 && token.identity() != null
                && token.identity().get("world_id") != null && !token.identity().get("world_id").isBlank()
                && token.identity().get("server_session") != null && !token.identity().get("server_session").isBlank();
    }

    public static Check check(Path configDir, UUID companion, Memo memo) {
        if (skillReview(memo)) return new Check(false, "REVIEW_CONTEXT_UNKNOWN");
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
