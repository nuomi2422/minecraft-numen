package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.Subtask;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 2026-11-09 044 §0.7: read-only early-completion predicate over the companion's own inventory counts.
 * It never mutates the chain and only accepts hard-coded item "hold" conditions; unknowns are skipped. */
public final class EarlyCompletion {
    private EarlyCompletion() {}

    public static List<Subtask> satisfied(TaskChain chain, Map<String, Integer> counts, long expectedPlanRevision) {
        if (chain == null || chain.planRevision() != expectedPlanRevision) return List.of();
        return satisfied(chain, counts);
    }

    /** Same scan, additionally binding the owning chain instance. */
    public static List<Subtask> satisfied(TaskChain chain, Map<String, Integer> counts,
                                          long expectedPlanRevision, String expectedChainInstanceId) {
        if (chain == null || expectedChainInstanceId == null
                || !expectedChainInstanceId.equals(chain.chainInstanceId())) return List.of();
        return satisfied(chain, counts, expectedPlanRevision);
    }

    public static List<Subtask> satisfied(TaskChain chain, Map<String, Integer> counts) {
        List<Subtask> out = new ArrayList<>();
        if (chain == null || counts == null) return out;
        for (Subtask task : chain.pendingSubtasks()) {
            Map<String, Object> c = task.condition();
            if (c == null || !"item".equals(c.get("type"))) continue;
            Object mode = c.get("mode");
            if (mode != null && !HardCodedEvaluator.MODE_HOLD.equals(String.valueOf(mode))) continue;
            Object source = c.get("source");
            if (source != null && !"inventory".equals(String.valueOf(source))) continue;
            if (HardCodedEvaluator.minimumOf(c) <= 0) continue;
            if (HardCodedEvaluator.matches(c, counts, null)) out.add(task);
        }
        return out;
    }
}
