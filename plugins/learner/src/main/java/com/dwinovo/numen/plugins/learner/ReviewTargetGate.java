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
        return skillTarget(configDir, companion, memo);
    }


    /** Current target permission is separate from the durable sample's historical world identity.
     * Skill lifecycle failures remain nonterminal: this gate must not invent a policy deleting old facts. */
    private static Check skillTarget(Path dir, UUID companion, Memo memo) {
        String ref, version, bodyRef = "", bodyDigest = "";
        try {
            if ("skill_task_result".equals(memo.stage())) {
                var fact = com.dwinovo.numen.plugins.learner.core.SkillDeliveryIntake.taskResultFromSnapshot(memo.snapshot());
                if (fact == null) return new Check(false, "SKILL_SNAPSHOT_UNKNOWN");
                if (!companion.toString().equals(fact.scope().companionId()))
                    return new Check(false, "SKILL_COMPANION_MISMATCH");
                ref = fact.ref(); version = fact.version();
                bodyRef = fact.bodyRef(); bodyDigest = fact.bodyDigest();
                if (bodyRef.isBlank() || bodyDigest.isBlank()) return new Check(false, "SKILL_BODY_UNKNOWN");
            } else {
                // Existing delivery memos contain the original detail object, not a new Entry schema.
                JsonObject detail = JsonParser.parseString(memo.snapshot()).getAsJsonObject();
                ref = text(detail, "skill_ref"); version = text(detail, "version");
            }
            if (!ref.matches("skill:[^\\s:@]+") || !version.matches("v[1-9][0-9]*"))
                return new Check(false, "SKILL_SNAPSHOT_UNKNOWN");
        } catch (RuntimeException malformed) {
            return new Check(false, "SKILL_SNAPSHOT_UNKNOWN");
        }
        if (dir == null) return new Check(false, "SKILL_REGISTRY_UNKNOWN");
        Path file = dir.resolve("skills.jsonl");
        if (!Files.isRegularFile(file)) return new Check(false, "SKILL_REGISTRY_UNKNOWN");
        try {
            // Read the current durable registry, not a possibly older writer cache.
            var registry = com.dwinovo.numen.plugins.learner.skills.SkillRegistry.open(file);
            if (registry.skippedLines() > 0) return new Check(false, "SKILL_REGISTRY_UNKNOWN");
            var skill = registry.get(ref.substring("skill:".length())).orElse(null);
            if (skill == null) return new Check(false, "SKILL_TARGET_MISSING");
            if (skill.status() == com.dwinovo.numen.plugins.learner.skills.SkillStatus.REVOKED)
                return new Check(false, "SKILL_REVOKED");
            if (!version.equals("v" + skill.version())) return new Check(false, "SKILL_VERSION_CHANGED");
            if (!skill.status().retrievable()) return new Check(false, "SKILL_NOT_RETRIEVABLE");
            String body = com.dwinovo.numen.plugins.learner.skills.SkillBodyStore.read(dir, skill.bodyRef());
            if (body == null || body.isBlank()) return new Check(false, "SKILL_BODY_UNKNOWN");
            if ("skill_task_result".equals(memo.stage()) && (!bodyRef.equals(skill.bodyRef())
                    || !bodyDigest.equals(SkillTaskFeedback.digest(body))))
                return new Check(false, "SKILL_BODY_MISMATCH");
            // Delivery has no historical body digest: current readability is not proof of old delivered bytes.
            return new Check(true, "OK");
        } catch (IOException | RuntimeException unreadable) {
            return new Check(false, "SKILL_REGISTRY_UNKNOWN");
        }
    }

    private static String text(JsonObject object, String key) {
        var value = object.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(key);
        return value.getAsString();
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
