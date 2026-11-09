package com.dwinovo.numen.api;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 2026-11-09: pure semantics + rate limit for knowledge_gap events. */
final class KnowledgeGaps {
    private static final Map<String, Long> LAST = new ConcurrentHashMap<>();
    private static final long COOLDOWN_MS = 60_000L;

    private KnowledgeGaps() {}

    /** null result is "no result", not a gap; blank with refs is a missing body. */
    static String reasonFor(NumenPlugins.KnowledgeFragment f, Throwable error) {
        if (error instanceof KnowledgeGapException ge) return ge.reason() == KnowledgeGapException.Reason.BODY_MISSING ? "body-missing" : "read-failed";
        if (error != null) return "read-failed";
        if (f == null) return null;
        if ((f.text() == null || f.text().isBlank()) && !f.refs().isEmpty()) return "body-missing";
        return null;
    }

    static boolean allow(UUID companion, int index, String reason, long nowMs) {
        String key = String.valueOf(companion) + "|" + index + "|" + reason;
        Long last = LAST.get(key);
        if (last != null && nowMs - last < COOLDOWN_MS) return false;
        LAST.put(key, nowMs);
        return true;
    }
}
