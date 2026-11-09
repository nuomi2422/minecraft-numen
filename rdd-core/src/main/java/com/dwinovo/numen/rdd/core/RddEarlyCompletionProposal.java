package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.Subtask;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Read-only proposal: facts already satisfy some pending subtasks; it never settles or skips anything. */
public final class RddEarlyCompletionProposal {
    public record Proposal(List<String> subtaskIds, long planRevision, String chainInstanceId,
                           long stateVersion, Map<String, Integer> evidence) {}

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
                chain.chainInstanceId(), chain.stateVersion(), evidence);
    }
}
