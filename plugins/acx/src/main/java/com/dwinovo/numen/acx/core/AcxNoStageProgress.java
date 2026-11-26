package com.dwinovo.numen.acx.core;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded observation of producer-declared stage progress; heartbeats never refresh it. */
public final class AcxNoStageProgress {
    public static final long DEFAULT_NO_PROGRESS_MS = 30_000L;
    public static final long MAX_OBSERVATION_MS = 600_000L;
    private static final long MIN_SAMPLE_INTERVAL_MS = 1_000L;
    private static final long MAX_NO_PROGRESS_MS = 300_000L;

    private final long startedAt;
    private long lastSampleAt;
    private long lastProgressAt;
    private String marker = "";
    private String stage = "UNKNOWN";
    private String waitReason = "async_accepted";
    private long noProgressBudgetMs = DEFAULT_NO_PROGRESS_MS;
    private int stagnantSamples;
    private boolean initialized;
    private boolean expired;

    public AcxNoStageProgress(long startedAt) {
        this(startedAt, Map.of());
    }

    public AcxNoStageProgress(long startedAt, Map<String, Object> initialFacts) {
        if (initialFacts == null) initialFacts = Map.of();
        this.startedAt = startedAt;
        this.lastSampleAt = startedAt;
        this.lastProgressAt = startedAt;
        this.marker = marker(initialFacts);
        this.stage = stage(initialFacts);
        this.waitReason = string(initialFacts.get("wait_reason"), "async_accepted");
        this.noProgressBudgetMs = budget(initialFacts);
        this.initialized = !marker.isEmpty();
    }

    public Result observe(Map<String, Object> facts, long now) {
        if (facts == null) facts = Map.of();
        if (expired) return expiredResult(now);
        if (now < lastSampleAt) return runningResult(now);
        String nextMarker = marker(facts);
        boolean supplied = !facts.isEmpty();
        if (supplied) {
            stage = string(facts.get("stage"), stage);
            waitReason = string(facts.get("wait_reason"), waitReason);
        }
        if (nextMarker.equals(marker) && now - lastSampleAt < MIN_SAMPLE_INTERVAL_MS) {
            return runningResult(now);
        }
        lastSampleAt = now;
        if (!initialized) {
            marker = nextMarker;
            noProgressBudgetMs = budget(facts);
            initialized = true;
        } else if (supplied && !nextMarker.equals(marker)) {
            marker = nextMarker;
            lastProgressAt = now;
            stagnantSamples = 0;
            noProgressBudgetMs = budget(facts);
        } else {
            stagnantSamples++;
        }
        if (now - lastProgressAt >= noProgressBudgetMs || now - startedAt >= MAX_OBSERVATION_MS) {
            expired = true;
            return expiredResult(now);
        }
        return runningResult(now);
    }

    public Map<String, Object> details(long now) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("stage", stage);
        out.put("wait_reason", waitReason);
        out.put("last_effective_progress_ms", Math.max(0, now - lastProgressAt));
        out.put("no_progress_budget_ms", noProgressBudgetMs);
        out.put("running_budget_ms", MAX_OBSERVATION_MS);
        out.put("stagnant_samples", stagnantSamples);
        out.put("expired", expired);
        return out;
    }

    private Result runningResult(long now) {
        return new Result(false, details(now));
    }

    private Result expiredResult(long now) {
        return new Result(true, details(now));
    }

    private static String marker(Map<String, Object> facts) {
        if (facts == null || facts.isEmpty()) return "";
        Object sequence = facts.get("progress_seq");
        String progress = sequence instanceof Number || sequence instanceof String ? String.valueOf(sequence) : "";
        return stage(facts) + "\u0000" + progress;
    }

    private static long budget(Map<String, Object> facts) {
        Object raw = facts == null ? null : facts.get("no_progress_budget_ms");
        if (!(raw instanceof Number number)) return DEFAULT_NO_PROGRESS_MS;
        return Math.max(MIN_SAMPLE_INTERVAL_MS, Math.min(MAX_NO_PROGRESS_MS, number.longValue()));
    }

    private static String stage(Map<String, Object> facts) {
        return string(facts == null ? null : facts.get("stage"), "UNKNOWN");
    }

    private static String string(Object value, String fallback) {
        return value instanceof String string && !string.isBlank() ? string : fallback;
    }

    public record Result(boolean expired, Map<String, Object> details) { }
}
