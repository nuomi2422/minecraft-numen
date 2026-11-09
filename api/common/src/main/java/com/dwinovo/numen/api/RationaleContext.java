package com.dwinovo.numen.api;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 2026-11-09 044 §0.7: structured "why" carrier for planner/executor final requests.
 * Absent/partial = omitted; consumers must not treat it as proof. */
public final class RationaleContext {
    public record Entry(String why, String risks, String source, String conditions) {}
    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();

    private RationaleContext() {}

    public static void set(UUID companionId, Entry entry) {
        if (companionId == null || entry == null) return;
        ENTRIES.put(companionId, entry);
    }
    public static Entry get(UUID companionId) { return companionId == null ? null : ENTRIES.get(companionId); }
    public static void clear(UUID companionId) { if (companionId != null) ENTRIES.remove(companionId); }

    public static String render(UUID companionId) {
        Entry e = get(companionId);
        if (e == null) return "";
        StringBuilder b = new StringBuilder("<rationale>");
        append(b, "why", e.why()); append(b, "risks", e.risks());
        append(b, "source", e.source()); append(b, "conditions", e.conditions());
        return b.append("</rationale>").toString();
    }
    private static void append(StringBuilder b, String k, String v) {
        if (v != null && !v.isBlank()) {
            String safe = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
            if (safe.length() > 500) safe = safe.substring(0, 500) + "...";
            b.append('<').append(k).append('>').append(safe).append("</").append(k).append('>');
        }
    }
}
