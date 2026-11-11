package com.dwinovo.numen.plugins.learner.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** 2026-11-08 bug-046A: skills used to keep only `draft:<id>:<hash>`; body was dropped.
 *  Persist the body atomically so migration can verify it; failure returns null (visible to caller). */
public final class SkillBodyStore {
    private SkillBodyStore() {}

    public static String write(Path configDir, String skillId, int version, String contentHash, String body) {
        if (configDir == null || skillId == null || skillId.isBlank() || body == null || body.isBlank()) return null;
        try {
            Path dir = configDir.resolve("skill-bodies");
            Files.createDirectories(dir);
            String safeId = skillId.replaceAll("[^A-Za-z0-9_-]", "_");
            String safeHash = (contentHash == null ? "nohash" : contentHash).replaceAll("[^A-Za-z0-9_-]", "_");
            String name = safeId + "@v" + version + "-" + safeHash + ".md";
            Path target = dir.resolve(name);
            Path tmp = dir.resolve(name + ".tmp");
            Files.writeString(tmp, body, StandardCharsets.UTF_8);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return "skill-body:" + name;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static String read(Path configDir, String bodyRef) {
        if (configDir == null || bodyRef == null || !bodyRef.startsWith("skill-body:")) return null;
        try {
            Path f = configDir.resolve("skill-bodies").resolve(bodyRef.substring("skill-body:".length()));
            if (!Files.exists(f)) return null;
            String body = Files.readString(f, StandardCharsets.UTF_8);
            // 2026-11-11: files may change after registration. Check the text read for delivery,
            // using the existing content fingerprint; legacy/nohash refs remain integrity UNKNOWN.
            var checksum = java.util.regex.Pattern.compile("@v\\d+-([0-9a-f]+)\\.md$").matcher(bodyRef);
            if (checksum.find() && !Integer.toHexString(body.hashCode()).equals(checksum.group(1))) return null;
            return body;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
