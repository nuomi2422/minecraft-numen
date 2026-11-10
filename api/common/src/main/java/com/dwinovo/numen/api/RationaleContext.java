package com.dwinovo.numen.api;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 2026-11-09 044 §0.7: structured "why" carrier for planner/executor final requests.
 * Absent/partial = omitted; consumers must not treat it as proof. */
public final class RationaleContext {
    public record Entry(String why, String risks, String source, String conditions) {}

    /**
     * One immutable snapshot per companion. Entry, generation and chain must be read
     * together: keeping them in separate maps let a concurrent write pair the old reason
     * with the new generation (or the new chain), so "this is the current why" could be
     * assembled from two different writes.
     */
    private record State(Entry entry, long generation, String chain) {}

    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    /**
     * Chain the host currently runs for this companion. Declared by the host on every context
     * build, because a plan revision counter restarts per chain: comparing numbers across chains
     * would let an old chain's high revision block a new chain's low one, and vice versa.
     */
    private static final Map<UUID, String> CURRENT_CHAIN = new ConcurrentHashMap<>();

    private RationaleContext() {}

    public static void set(UUID companionId, Entry entry) {
        if (companionId == null || entry == null) return;
        STATES.compute(companionId, (id, old) -> new State(entry,
                old == null ? 1L : old.generation() + 1L, old == null ? null : old.chain()));
    }

    /** Higher generation wins; stale async callbacks are ignored. */
    public static void set(UUID companionId, Entry entry, long generation) {
        set(companionId, entry, generation, null);
    }

    /** Host declares which chain it is actually running; stale callbacks from other chains are then refused. */
    public static void noteCurrentChain(UUID companionId, String chainInstanceId) {
        if (companionId == null) return;
        if (chainInstanceId == null || chainInstanceId.isBlank()) CURRENT_CHAIN.remove(companionId);
        else CURRENT_CHAIN.put(companionId, chainInstanceId);
    }

    /** Scoped write: the entry belongs to one chain instance. Legacy setters stay unscoped. */
    public static void set(UUID companionId, Entry entry, long generation, String chainInstanceId) {
        if (companionId == null || entry == null) return;
        boolean scoped = chainInstanceId != null && !chainInstanceId.isBlank();
        STATES.compute(companionId, (id, old) -> {
            if (old == null) return new State(entry, generation, scoped ? chainInstanceId : null);
            if (!scoped) {
                // Legacy unscoped writes keep the old rule: a lower generation never overwrites.
                return generation < old.generation() ? old : new State(entry, generation, old.chain());
            }
            String oldChain = old.chain();
            if (oldChain != null && oldChain.equals(chainInstanceId)) {
                // Revision counters restart per chain, so only same-chain writes may be ordered by it.
                return generation < old.generation() ? old : new State(entry, generation, chainInstanceId);
            }
            // A write for a different chain. Upgrade an unscoped entry; otherwise only the chain the
            // host declared as current may replace it — a finished chain's late callback may not.
            if (oldChain == null) return new State(entry, generation, chainInstanceId);
            String current = CURRENT_CHAIN.get(id);
            return current != null && current.equals(chainInstanceId)
                    ? new State(entry, generation, chainInstanceId) : old;
        });
    }

    private static State state(UUID companionId) { return companionId == null ? null : STATES.get(companionId); }

    public static Entry get(UUID companionId) {
        State s = state(companionId);
        return s == null ? null : s.entry();
    }

    /** Chain instance the current entry was written for; null when unscoped/unknown. */
    public static String chain(UUID companionId) {
        State s = state(companionId);
        return s == null ? null : s.chain();
    }

    public static long generation(UUID companionId) {
        State s = state(companionId);
        return s == null ? 0L : s.generation();
    }

    /**
     * Only a scoped entry from the same chain instance and plan revision may be shown as
     * current. Unscoped legacy entries and unknown chains are not fresh — absence of
     * evidence is not evidence.
     */
    public static boolean applicable(UUID companionId, long planRevision, String chainInstanceId) {
        return applicable(state(companionId), planRevision, chainInstanceId);
    }

    private static boolean applicable(State s, long planRevision, String chainInstanceId) {
        if (s == null || s.entry() == null) return false;
        if (chainInstanceId == null || chainInstanceId.isBlank()) return false;
        if (s.generation() != planRevision) return false;
        return chainInstanceId.equals(s.chain());
    }

    /**
     * Atomic check-and-render: the decision and the text come from ONE snapshot, so a
     * concurrent write cannot pass the check with the new state and render the old entry.
     */
    public static String renderApplicable(UUID companionId, long planRevision, String chainInstanceId) {
        State s = state(companionId);
        if (!applicable(s, planRevision, chainInstanceId)) return "";
        return renderEntry(s.entry());
    }

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
        STATES.remove(companionId);
        CURRENT_CHAIN.remove(companionId);
    }

    public static String render(UUID companionId) {
        State s = state(companionId);
        return s == null ? "" : renderEntry(s.entry());
    }

    private static String renderEntry(Entry e) {
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
