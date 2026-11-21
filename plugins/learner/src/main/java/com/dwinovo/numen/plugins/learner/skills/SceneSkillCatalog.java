package com.dwinovo.numen.plugins.learner.skills;

import com.dwinovo.numen.api.LearnedSkillBodyResolver;
import java.util.UUID;

import java.util.Map;

/**
 * Existing registry metadata for scene references, not a second registry or executor.
 * Preconditions keep the 053 markers: scene:<name>:package/component and
 * requires:/optional:skill:<id>@v<n>. Discovery never executes a referenced body.
 */
final class SceneSkillCatalog {
    private static final Map<String, String> GOAL_ALIASES = Map.of("overnight", "安家|过夜|入睡|睡前|睡觉|就寝|避一晚");

    private SceneSkillCatalog() {}

    static String kind(SkillEntry entry) {
        String scene = scene(entry);
        if (scene.isEmpty()) {
            return entry.preconditions().stream().anyMatch(c -> c != null && c.startsWith("scene:")) ? "invalid" : "";
        }
        boolean pack = entry.preconditions().contains("scene:" + scene + ":package");
        boolean component = entry.preconditions().contains("scene:" + scene + ":component");
        return pack == component ? "invalid" : pack ? "package" : "component";
    }

    static String scene(SkillEntry entry) {
        String found = "";
        for (String condition : entry.preconditions()) {
            String[] parts = condition == null ? new String[0] : condition.split(":", -1);
            if (parts.length != 3 || !"scene".equals(parts[0]) || parts[1].isBlank()
                    || !("package".equals(parts[2]) || "component".equals(parts[2]))) continue;
            if (!found.isEmpty() && !found.equals(parts[1])) return "";
            found = parts[1];
        }
        return found;
    }

    static String goalAlias(String scene) {
        return GOAL_ALIASES.getOrDefault(scene, "(?!x)x");
    }

    static boolean missingRequired(SkillEntry entry, LearnedSkillBodyResolver resolver, UUID companion, LearnedSkillBodyResolver.View view) {
        for (String condition : entry.preconditions()) {
            if (condition.startsWith("requires:") && !available(condition.substring("requires:".length()), resolver, companion, view)) {
                return true;
            }
        }
        return false;
    }

    static String availability(SkillEntry entry, LearnedSkillBodyResolver resolver, UUID companion, LearnedSkillBodyResolver.View view) {
        if ("package".equals(kind(entry)) && entry.preconditions().stream()
                .filter(c -> c.startsWith("optional:"))
                .anyMatch(c -> !available(c.substring("optional:".length()), resolver, companion, view))) {
            return "degraded";
        }
        return "ready";
    }

    private static boolean available(String exactRef, LearnedSkillBodyResolver resolver, UUID companion, LearnedSkillBodyResolver.View view) {
        var result = resolver.resolve(companion, exactRef, view);
        return result != null && exactRef.equals(result.exactRef()) && result.available();
    }
}
