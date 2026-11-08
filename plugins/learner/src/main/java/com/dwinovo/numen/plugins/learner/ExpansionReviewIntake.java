package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/** 2026-11-08 C1: bounded consumer that turns successful experience_expand deliveries into learner review memos.
 *  Queue entries are removed only after the memo is enqueued (or already present). */
public final class ExpansionReviewIntake {
    public static final String FILE = "expansion-review-queue.jsonl";
    private static final int MAX_PER_SCAN = 2;

    private ExpansionReviewIntake() {}

    public static int scan(Path configDir, Function<UUID, MemoQueue> queues) {
        if (configDir == null || queues == null) return 0;
        Path f = configDir.resolve(FILE);
        if (!Files.isRegularFile(f)) return 0;
        int admitted = 0;
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            List<String> rest = new ArrayList<>();
            for (String line : lines) {
                if (line == null || line.isBlank()) continue;
                if (admitted >= MAX_PER_SCAN) { rest.add(line); continue; }
                JsonObject o;
                try { o = JsonParser.parseString(line).getAsJsonObject(); }
                catch (RuntimeException bad) { rest.add(line); continue; }
                String cs = str(o, "companion"), ref = str(o, "ref");
                String version = str(o, "version"), use = str(o, "use_id");
                UUID id;
                try { id = UUID.fromString(cs); } catch (RuntimeException bad) { rest.add(line); continue; }
                MemoQueue q = queues.apply(id);
                if (q == null) { rest.add(line); continue; }
                String unavailable = unavailableReason(configDir, id, ref);
                if (unavailable != null) {
                    com.dwinovo.numen.plugins.learner.LearnerMonitor.publish("expansion_review_skipped",
                            java.util.Map.of("ref", ref, "version", version, "use_id", use, "reason", unavailable));
                    continue; // consumed: retracted/missing must not be retried
                }
                String memoId = "expand-review:" + ref + "@" + version + "#" + use;
                Memo memo = new Memo(memoId, "复核展开正文 " + ref, "EXPERIENCE_EXPAND",
                        "ref=" + ref + " version=" + version + " use_id=" + use,
                        "expansion review requested after expand delivery", System.currentTimeMillis());
                boolean ok;
                try { ok = q.append(memo); } catch (RuntimeException e) { ok = false; }
                if (ok || q.contains(memoId)) {
                    admitted++;
                } else {
                    rest.add(line);
                }
            }
            Path tmp = f.resolveSibling(FILE + ".tmp");
            Files.writeString(tmp, rest.isEmpty() ? "" : String.join(System.lineSeparator(), rest) + System.lineSeparator(),
                    StandardCharsets.UTF_8);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            return 0;
        }
        return admitted;
    }

    /** null = still available; otherwise skip reason. Version mismatch is left to review (not encoded in JSON). */
    private static String unavailableReason(Path configDir, UUID companion, String ref) {
        if (!ref.startsWith("exp:")) return "unsupported_ref";
        String id = ref.substring(4);
        Path f = configDir.resolve("experience-" + companion + ".jsonl");
        if (!Files.isRegularFile(f)) return "experience_file_missing";
        try {
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line == null || !line.contains("\"id\":\"" + id + "\"")) continue;
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                if (o.has("retracted") && o.get("retracted").getAsBoolean()) return "retracted";
                String pub = str(o, "publication");
                if ("REVOKED".equalsIgnoreCase(pub)) return "revoked";
                return null;
            }
            return "missing";
        } catch (IOException | RuntimeException e) {
            return "read_failed";
        }
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
}
