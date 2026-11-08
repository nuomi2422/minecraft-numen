package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** 2026-11-08 bug-17: durable batch claim so a hard kill can restore drained memos.
 *  One file per review; only deleted after terminal/restore succeeds. */
public final class ReviewBatchStore {
    private static final Gson GSON = new Gson();
    private static final java.lang.reflect.Type LIST = new TypeToken<List<Memo>>() {}.getType();

    private ReviewBatchStore() {}

    public static Path file(Path configDir, String reviewId) {
        return configDir.resolve("review-batch-" + reviewId + ".json");
    }

    public static boolean save(Path configDir, String reviewId, List<Memo> batch) {
        if (configDir == null || reviewId == null || batch == null) return false;
        try {
            Files.createDirectories(configDir);
            Path target = file(configDir, reviewId);
            Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(batch, LIST), StandardCharsets.UTF_8);
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    public static List<Memo> load(Path configDir, String reviewId) {
        if (configDir == null || reviewId == null) return List.of();
        try {
            Path f = file(configDir, reviewId);
            if (!Files.isRegularFile(f)) return List.of();
            List<Memo> got = GSON.fromJson(Files.readString(f, StandardCharsets.UTF_8), LIST);
            return got == null ? List.of() : List.copyOf(got);
        } catch (IOException | RuntimeException e) {
            return List.of();
        }
    }

    public static boolean delete(Path configDir, String reviewId) {
        try {
            return configDir != null && reviewId != null && Files.deleteIfExists(file(configDir, reviewId));
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }
}
