package com.dwinovo.numen.plugins.rdd;

import java.util.Map;
import java.util.UUID;

/** 2026-11-07 B2: bounded hint after repeated empty harvests with no crop growth progress. */
final class RddFarmScanGuard {
    private RddFarmScanGuard() {}

    static final class State {
        int zeroCalls;
        long firstMs;
        long fingerprint;
    }

    static final long WINDOW_MS = 20 * 60 * 1000L;
    static final int MIN_ZERO_CALLS = 3;

    /** @return hint text when 3+ empty scans over >=20min show no age/progress change; null otherwise. */
    static String observe(Map<UUID, State> states, UUID id, long nowMs, int harvested, long fingerprint) {
        if (id == null) return null;
        State s = states.computeIfAbsent(id, k -> new State());
        if (harvested > 0) { reset(s); return null; }
        if (s.zeroCalls == 0) {
            s.zeroCalls = 1; s.firstMs = nowMs; s.fingerprint = fingerprint;
            return null;
        }
        if (fingerprint != s.fingerprint) { // age/position changed = growth progress
            reset(s);
            s.zeroCalls = 1; s.firstMs = nowMs; s.fingerprint = fingerprint;
            return null;
        }
        s.zeroCalls++;
        if (s.zeroCalls >= MIN_ZERO_CALLS && nowMs - s.firstMs >= WINDOW_MS) {
            s.zeroCalls = 0; s.firstMs = 0;
            return "连续 " + MIN_ZERO_CALLS + " 次收割为空且 >=20 分钟无生长变化：考虑扩田/修水/换地块/先存箱；正常生长等待中忽略本提示。";
        }
        return null;
    }

    private static void reset(State s) { s.zeroCalls = 0; s.firstMs = 0; s.fingerprint = 0; }
}
