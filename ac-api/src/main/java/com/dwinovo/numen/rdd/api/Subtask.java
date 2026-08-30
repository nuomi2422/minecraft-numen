package com.dwinovo.numen.rdd.api;

import java.util.Map;

public record Subtask(
        String id,
        String description,
        DetectionMode detectionMode,
        Map<String, Object> condition,
        long detectionIntervalSeconds,
        int maxAiChecks,
        boolean recheckOnUnchanged) {
    public Subtask {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("subtask id required");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("subtask description required");
        if (detectionMode == null) throw new IllegalArgumentException("detection mode required");
        condition = condition == null ? Map.of() : Map.copyOf(condition);
        if (detectionMode == DetectionMode.HARD_CODED && condition.isEmpty()) {
            throw new IllegalArgumentException("hard-coded subtask condition required");
        }
        if (detectionMode == DetectionMode.AI_ASSISTED && (detectionIntervalSeconds <= 0 || maxAiChecks <= 0)) {
            throw new IllegalArgumentException("AI-assisted subtask interval and max checks must be positive");
        }
    }

    public static Subtask hardCoded(String id, String description, Map<String, Object> condition) {
        return new Subtask(id, description, DetectionMode.HARD_CODED, condition, 0, 0, false);
    }

    public static Subtask aiAssisted(String id, String description, long intervalSeconds, int maxChecks,
                                     boolean recheckOnUnchanged) {
        return new Subtask(id, description, DetectionMode.AI_ASSISTED, Map.of(), intervalSeconds, maxChecks,
                recheckOnUnchanged);
    }
}
