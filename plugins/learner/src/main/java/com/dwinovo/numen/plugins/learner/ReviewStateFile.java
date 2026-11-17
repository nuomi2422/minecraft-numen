package com.dwinovo.numen.plugins.learner;

import java.nio.file.Path;
import com.dwinovo.numen.plugins.learner.LearnerPlugin.ReviewTimes;
import com.dwinovo.numen.plugins.learner.LearnerPlugin.ReviewStateRead;
import static com.dwinovo.numen.plugins.learner.LearnerPlugin.REVIEW_STATE_LOCK;
import static com.dwinovo.numen.plugins.learner.LearnerPlugin.LOG;

/** The existing learner-review-state.json read/merge/write contract.
 * No additional state file, cache, scheduling policy or batch-claim store.
 * Uses the original host lock and result types; reads intentionally retain their original lock scope. */
final class ReviewStateFile {
    private ReviewStateFile() {}

    static void saveReviewState(Path dir, String at, int verdictCount) {
        if (dir == null) return;
        try {
            java.nio.file.Files.createDirectories(dir);
            mergeState(dir, root -> {
                root.addProperty("last_review_at", at == null ? "" : at);
                root.addProperty("last_verdict_count", verdictCount);
            });
            LOG.info("[learner] review state written {}", reviewStateFile(dir));
        } catch (RuntimeException | java.io.IOException e) {
            // 2026-11-08 bug-12: do not swallow silently; state was unreadable while reviews completed.
            LOG.warn("[learner] review state write failed dir={}: {}", dir, e.toString());
        }
    }

    static java.nio.file.Path reviewStateFile(java.nio.file.Path dir) {
        return dir == null ? null : dir.resolve("learner-review-state.json");
    }

    static com.google.gson.JsonObject readStateObject(java.nio.file.Path dir) throws java.io.IOException {
        java.nio.file.Path f = reviewStateFile(dir);
        if (f == null) throw new java.io.IOException("review state dir unavailable");
        String raw;
        try {
            raw = java.nio.file.Files.readString(f, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.nio.file.NoSuchFileException missing) {
            return null; // only a confirmed absent file is a known empty migration
        }
        // Directory/permission/etc. propagate as unreadable -> UNKNOWN, never "missing".
        return com.google.gson.JsonParser.parseString(raw).getAsJsonObject();
    }

    static void mergeState(java.nio.file.Path dir,
                                   java.util.function.Consumer<com.google.gson.JsonObject> mutate) throws java.io.IOException {
        if (dir == null || reviewStateFile(dir) == null) throw new java.io.IOException("review state dir unavailable");
        java.nio.file.Files.createDirectories(dir);
        synchronized (REVIEW_STATE_LOCK) {
            com.google.gson.JsonObject root = readStateObject(dir);
            if (root == null) root = new com.google.gson.JsonObject();
            mutate.accept(root);
            java.nio.file.Path tmp = dir.resolve("learner-review-state.json.tmp");
            java.nio.file.Files.writeString(tmp, root.toString(), java.nio.charset.StandardCharsets.UTF_8);
            java.nio.file.Files.move(tmp, reviewStateFile(dir), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
    }

    static com.google.gson.JsonObject companionNode(com.google.gson.JsonObject root, java.util.UUID companion) {
        com.google.gson.JsonElement existing = root.get("companions");
        if (existing != null && !existing.isJsonObject()) {
            throw new IllegalArgumentException("companions is not an object");
        }
        com.google.gson.JsonObject companions = existing == null
                ? new com.google.gson.JsonObject() : existing.getAsJsonObject();
        root.add("companions", companions);
        String key = String.valueOf(companion);
        com.google.gson.JsonElement owner = companions.get(key);
        if (owner != null && !owner.isJsonObject()) {
            throw new IllegalArgumentException("companion node is not an object");
        }
        com.google.gson.JsonObject node = owner == null
                ? new com.google.gson.JsonObject() : owner.getAsJsonObject();
        companions.add(key, node);
        for (String field : new String[] {"last_nudge_at", "last_auto_review_at", "last_review_started_at"}) {
            if (clock(node, field) == null) throw new IllegalArgumentException("invalid clock " + field);
        }
        return node;
    }

    static boolean writeCompanionTime(java.nio.file.Path dir, java.util.UUID companion, String field, long at) {
        if (dir == null || companion == null) return false;
        try {
            mergeState(dir, root -> companionNode(root, companion).addProperty(field, at));
            return true;
        } catch (RuntimeException | java.io.IOException e) {
            LOG.warn("[learner] review state write failed dir={} field={}: {}", dir, field, e.toString());
            return false;
        }
    }

    static boolean rememberNudge(java.nio.file.Path dir, java.util.UUID companion, long at) {
        return writeCompanionTime(dir, companion, "last_nudge_at", at);
    }

    static boolean rememberReviewStarted(java.nio.file.Path dir, java.util.UUID companion, long at) {
        return writeCompanionTime(dir, companion, "last_review_started_at", at);
    }

    static boolean rememberAutoReview(java.nio.file.Path dir, java.util.UUID companion, long at) {
        return writeCompanionTime(dir, companion, "last_auto_review_at", at);
    }

    static ReviewStateRead readReviewTimes(java.nio.file.Path dir, java.util.UUID companion) {
        if (dir == null || companion == null) return ReviewStateRead.unknown();
        com.google.gson.JsonObject root;
        try {
            root = readStateObject(dir);
        } catch (RuntimeException | java.io.IOException e) {
            return ReviewStateRead.unknown(); // never claim "not nudged" from a corrupt file
        }
        if (root == null) return ReviewStateRead.none();
        com.google.gson.JsonElement companionsEl = root.get("companions");
        if (companionsEl == null) return ReviewStateRead.none();
        if (companionsEl.isJsonNull()) return ReviewStateRead.unknown();
        if (!companionsEl.isJsonObject()) return ReviewStateRead.unknown();
        com.google.gson.JsonObject companions = companionsEl.getAsJsonObject();
        String key = String.valueOf(companion);
        com.google.gson.JsonElement owner = companions.get(key);
        if (owner == null) return ReviewStateRead.none();
        if (owner.isJsonNull()) return ReviewStateRead.unknown();
        if (!owner.isJsonObject()) return ReviewStateRead.unknown();
        com.google.gson.JsonObject node = owner.getAsJsonObject();
        Long nudge = clock(node, "last_nudge_at");
        Long auto = clock(node, "last_auto_review_at");
        Long started = clock(node, "last_review_started_at");
        if (nudge == null || auto == null || started == null) return ReviewStateRead.unknown();
        return new ReviewStateRead(true, new ReviewTimes(nudge, auto, started));
    }

    static Long clock(com.google.gson.JsonObject node, String name) {
        var v = node.get(name);
        if (v == null) return 0L; // absent key keeps the old migration value
        if (v.isJsonNull()) return null; // explicit null is a bad type, not "missing"
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) return null;
        try {
            long value = v.getAsBigDecimal().longValueExact();
            return value < 0 ? null : value;
        } catch (ArithmeticException | NumberFormatException invalid) {
            return null;
        }
    }
}
