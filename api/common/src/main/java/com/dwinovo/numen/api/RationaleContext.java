package com.dwinovo.numen.api;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 2026-11-09 044 §0.7: structured "why" carrier for planner/executor final requests.
 * Absent/partial = omitted; consumers must not treat it as proof. */
public final class RationaleContext {
    public record Entry(String why, String risks, String source, String conditions) {}
    private static final Map<UUID, Entry> ENTRIES = new ConcurrentHashMap<>();
    private static final Map<UUID, Long> GENERATIONS = new ConcurrentHashMap<>();

    private RationaleContext() {}

    public static void set(UUID companionId, Entry entry) {
        if (companionId == null || entry == null) return;
        long g = GENERATIONS.merge(companionId, 1L, Long::sum);
        ENTRIES.put(companionId, entry);
    }

    /** Higher generation wins; stale async callbacks are ignored. */
    public static void set(UUID companionId, Entry entry, long generation) {
        if (companionId == null || entry == null) return;
        long[] accepted = {0};
        GENERATIONS.compute(companionId, (id, old) -> {
            long next = old == null ? generation : Math.max(old, generation);
            if (old == null || generation >= old) { ENTRIES.put(id, entry); accepted[0] = 1; }
            return next;
        });
    }
    public static Entry get(UUID companionId) { return companionId == null ? null : ENTRIES.get(companionId); }
    public static long generation(UUID companionId) { return companionId == null ? 0L : GENERATIONS.getOrDefault(companionId, 0L); }

    /** Reject generic restatements that are not a causal reason. Heuristic, not proof. */
    public static String whyStatus(Entry e) {
        if (e == null || e.why() == null || e.why().isBlank()) return "ABSENT";
        return substantiveWhy(e.why()) ? "PRESENT" : "GENERIC";
    }

    public static boolean substantiveWhy(String why) {
        if (why == null) return false;
        String t = why.trim().toLowerCase(java.util.Locale.ROOT);
        if (t.length() < 3) return false;
        return !(t.equals("按计划") || t.equals("按计划继续") || t.equals("继续") || t.equals("用户说") || t.equals("主人说")
                || t.equals("as planned") || t.equals("continue") || t.equals("user said")
                || t.equals("wie geplant") || t.equals("comme prévu") || t.equals("según lo previsto")
                || t.equals("как планировалось"));
    }
    public static void clear(UUID companionId) {
        if (companionId == null) return;
        ENTRIES.remove(companionId);
        GENERATIONS.remove(companionId);
    }

    public static String render(UUID companionId) {
        Entry e = get(companionId);
        if (e == null) return "";
        StringBuilder b = new StringBuilder("<rationale>");
        if (substantiveWhy(e.why())) append(b, "why", e.why());
        append(b, "risks", e.risks());
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
