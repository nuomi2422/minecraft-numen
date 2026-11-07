package com.dwinovo.numen.agent.http;

/** 2026-11-07: bounded exponential retry for 429/timeout/IO. Base 2/4/8/16s, cap 60s, total 5min. */
public final class LlmRetryPolicy {
    public static final long BASE_MS = 2_000L;
    public static final long CAP_MS = 60_000L;
    public static final long TOTAL_CAP_MS = 300_000L;
    public static final int MAX_ATTEMPTS = 12;

    private LlmRetryPolicy() {}

    public record Decision(boolean retry, long delayMs, boolean exhausted) {}

    public static long baseDelayMs(int attempt) {
        if (attempt <= 0) return BASE_MS;
        int shift = Math.min(attempt, 5);
        return Math.min(CAP_MS, BASE_MS << shift);
    }

    /** Jitter in [0.8,1.2); deterministic tests should use baseDelayMs. */
    public static long jitteredDelayMs(int attempt, double rnd) {
        long base = baseDelayMs(attempt);
        double factor = 0.8 + 0.4 * Math.max(0.0, Math.min(1.0, rnd));
        return Math.max(1L, Math.round(base * factor));
    }

    /** attempt = failures already seen (0 = first failure). */
    public static Decision decide(int attempt, long elapsedMs, double rnd) {
        long delay = jitteredDelayMs(attempt, rnd);
        if (attempt >= MAX_ATTEMPTS || elapsedMs + delay > TOTAL_CAP_MS) {
            return new Decision(false, 0L, true);
        }
        return new Decision(true, delay, false);
    }
}
