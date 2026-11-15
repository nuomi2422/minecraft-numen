package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.api.LearnedSkillBodyResolver;
import com.dwinovo.numen.plugins.learner.core.LearnedLocationBinding;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/**
 * Explicit body reads use the learner's original registry and delivery checks.
 * No second store, history fallback, adoption receipt or recursive package loading.
 * Each call re-reads current eligibility, so revoked or revised entries cannot leak via cache.
 */
public final class LearnedSkillBodyResolverImpl implements LearnedSkillBodyResolver {
    private final Path configDir;

    public LearnedSkillBodyResolverImpl(Path configDir) {
        this.configDir = Objects.requireNonNull(configDir);
    }

    @Override
    public Resolution resolve(UUID companion, String exactRef, View consumerView) {
        if (companion == null) return Resolution.refused(exactRef, "companion-missing");
        if (consumerView == null) return Resolution.refused(exactRef, "consumer-view-missing");
        final Reference requested;
        try {
            requested = Reference.parse(exactRef);
        } catch (IllegalArgumentException ex) {
            return Resolution.refused(exactRef, "invalid-reference");
        }
        try {
            var registry = SkillRegistry.open(configDir.resolve("skills.jsonl"));
            var entry = registry.get(requested.skillId()).orElse(null);
            if (entry == null) return Resolution.refused(exactRef, "skill-missing");
            if (entry.version() != requested.version()) return Resolution.refused(exactRef, "version-mismatch");
            if (!entry.status().retrievable()) return Resolution.refused(exactRef, "not-retrievable");
            // Reuse the original audience rules, including rejection of unknown tokens.
            if (!new SkillsKnowledgeContributor(configDir, consumerView.name(), 0).audienceMatches(entry.audience()))
                return Resolution.refused(exactRef, "audience-mismatch");
            String body = SkillBodyStore.read(configDir, entry.bodyRef());
            if (body == null || body.isBlank()) return Resolution.refused(exactRef, "body-missing-or-invalid");
            if (!LearnedLocationBinding.problem(body).isEmpty())
                return Resolution.refused(exactRef, "location-binding-required");
            return new Resolution(exactRef, body, "");
        } catch (IOException | RuntimeException ex) {
            return Resolution.refused(exactRef, "registry-read-failed");
        }
    }
}
