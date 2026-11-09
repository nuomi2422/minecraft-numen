package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.Subtask;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only proposal: facts already satisfy some pending subtasks; it never settles or skips anything. */
public final class RddEarlyCompletionProposal {
    public record Proposal(List<String> subtaskIds, long planRevision, String chainInstanceId,
                           long stateVersion, Map<String, Integer> evidence, long sampledAtMillis) {}

    /** Aligns with the existing carry-hint stale window (RddCarryHint.STALE_MS = 10s); not a new policy constant. */
    public static final long STALE_MS = 10_000L;

    private RddEarlyCompletionProposal() {}

    public static Proposal propose(TaskChain chain, Map<String, Integer> inventoryCounts) {
        if (chain == null || inventoryCounts == null) return null;
        var hit = EarlyCompletion.satisfied(chain, inventoryCounts, chain.planRevision(), chain.chainInstanceId());
        if (hit.isEmpty()) return null;
        Map<String, Integer> evidence = new LinkedHashMap<>();
        for (Subtask s : hit) {
            Object item = s.condition() == null ? null : s.condition().get("item");
            if (item != null) evidence.put(String.valueOf(item), inventoryCounts.getOrDefault(String.valueOf(item), 0));
        }
        return new Proposal(hit.stream().map(Subtask::id).toList(), chain.planRevision(),
                chain.chainInstanceId(), chain.stateVersion(), evidence, System.currentTimeMillis());
    }

    /** Planner-readable, never verified: matching request identity -> UNVERIFIED; absent/mismatch/expired -> UNKNOWN. */
    public static String render(Proposal p, String currentWorld, String currentSession, long nowMillis) {
        if (p == null) return "";
        if (currentWorld == null || currentSession == null
                || nowMillis - p.sampledAtMillis() < 0 || nowMillis - p.sampledAtMillis() > STALE_MS) {
            return "<early_completion_proposal status=\"UNKNOWN\"/>";
        }
        return "<early_completion_proposal status=\"UNVERIFIED\" chain=\"" + p.chainInstanceId()
                + "\" plan_revision=\"" + p.planRevision() + "\" sampled_at_ms=\"" + p.sampledAtMillis()
                + "\">" + String.join(",", p.subtaskIds()) + "</early_completion_proposal>";
    }
}
