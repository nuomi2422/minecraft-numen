package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.ResourceUse;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.SubtaskStatus;
import com.dwinovo.numen.rdd.api.NodeTypePolicy;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Local plan purposes, not a transaction ledger. COMPLETED ends an input purpose; it does not
 * prove which items were consumed, transferred, or lost. Missing/old declarations stay conservative. */
final class RddResourceUseProjection {
    private RddResourceUseProjection() {}

    record Projection(Set<String> replacedAcquisitions, Set<String> delegatedWaitFor,
                      Map<String, Integer> material, Map<String, Integer> retained,
                      Set<String> declaredKeys) {
        int required(String key) { return add(material.getOrDefault(key, 0), retained.getOrDefault(key, 0)); }
    }

    static Projection of(PrimaryGoal primary, Map<String, SubtaskStatus> statuses) {
        Map<String, Integer> material = new LinkedHashMap<>();
        Map<String, Integer> retained = new LinkedHashMap<>();
        Map<String, Integer> declaredInput = new LinkedHashMap<>();
        Set<String> declared = new LinkedHashSet<>();
        Map<String, Integer> producers = new LinkedHashMap<>();
        Set<String> replaced = new LinkedHashSet<>();
        Set<String> delegated = new LinkedHashSet<>();
        if (primary == null) return new Projection(Set.of(), Set.of(), Map.of(), Map.of(), Set.of());
        List<Subtask> steps = primary.subtasks();
        for (Subtask step : steps) {
            if (!NodeTypePolicy.countsTowardMainline(step.nodeType())) continue;
            if (step.condition().get("asset_key") instanceof String key) producers.merge(key, 1, Integer::sum);
            for (ResourceUse use : step.resourceUses()) {
                declared.add(use.assetKey());
                if (use.kind() == ResourceUse.Kind.RETAIN) {
                    retained.merge(use.assetKey(), use.quantity(), Math::max);
                } else {
                    declaredInput.merge(use.assetKey(), use.quantity(), RddResourceUseProjection::add);
                    // SKIPPED/failed/unknown are not proof of the consumer's result.
                    if (!completed(step, statuses))
                        material.merge(use.assetKey(), use.quantity(), RddResourceUseProjection::add);
                }
            }
        }
        for (int i = 0; i < steps.size(); i++) {
            Subtask step = steps.get(i);
            if (!completed(step, statuses) || !NodeTypePolicy.countsTowardMainline(step.nodeType())
                    || !(step.condition().get("asset_key") instanceof String key)
                    || producers.getOrDefault(key, 0) != 1) continue;
            Object rawMinimum = step.condition().get("minimum");
            int minimum = rawMinimum instanceof Number number ? Math.max(1, number.intValue()) : 1;
            int laterPurpose = 0;
            for (int j = i + 1; j < steps.size(); j++) {
                Subtask consumer = steps.get(j);
                if (!NodeTypePolicy.countsTowardMainline(consumer.nodeType())) continue;
                for (ResourceUse use : consumer.resourceUses()) {
                    if (use.assetKey().equals(key) && use.kind() != ResourceUse.Kind.RETAIN)
                        laterPurpose = add(laterPurpose, use.quantity());
                }
            }
            // Do not infer a source relationship for repeated acquisitions or unexplained surplus.
            if (laterPurpose >= minimum) replaced.add(step.id());
        }
        for (var wait : primary.waitFor()) {
            if (wait != null && declaredInput.getOrDefault(wait.assetKey(), 0) >= wait.minimum())
                delegated.add(wait.assetKey());
        }
        return new Projection(Set.copyOf(replaced), Set.copyOf(delegated), Map.copyOf(material),
                Map.copyOf(retained), Set.copyOf(declared));
    }

    static boolean completed(Subtask step, Map<String, SubtaskStatus> statuses) {
        return statuses != null && statuses.get(step.id()) == SubtaskStatus.COMPLETED;
    }

    /** A missing planned input alone is not confirmed loss. Independent held obligations remain. */
    static boolean pendingMaterialOnly(PrimaryGoal primary, Map<String, SubtaskStatus> statuses,
                                       String key, int held) {
        Projection p = of(primary, statuses);
        if (p.material().getOrDefault(key, 0) <= 0 || held < p.retained().getOrDefault(key, 0)) return false;
        for (var wait : primary.waitFor()) {
            if (wait != null && key.equals(wait.assetKey()) && !p.delegatedWaitFor().contains(key)
                    && held < wait.minimum()) return false;
        }
        for (Subtask step : primary.subtasks()) {
            if (!NodeTypePolicy.countsTowardMainline(step.nodeType())) continue;
            if (!p.replacedAcquisitions().contains(step.id()) && key.equals(step.condition().get("asset_key"))) {
                Object raw = step.condition().get("minimum");
                int minimum = raw instanceof Number number ? Math.max(1, number.intValue()) : 1;
                if (held < minimum) return false;
            }
        }
        return true;
    }

    static String describe(PrimaryGoal primary, Map<String, SubtaskStatus> statuses) {
        if (primary == null) return "";
        StringBuilder text = new StringBuilder();
        int shown = 0;
        for (Subtask step : primary.subtasks()) {
            if (!NodeTypePolicy.countsTowardMainline(step.nodeType())) continue;
            for (ResourceUse use : step.resourceUses()) {
                if (shown++ == 4) return text.append("…</resource_purpose_state>").toString();
                if (text.isEmpty()) text.append("<resource_purpose_state>计划用途不是变化事实；数量减少原因未知，结果沿原世界判据核验。\n");
                text.append(step.id()).append(' ').append(use.kind()).append(' ').append(use.assetKey())
                        .append('×').append(use.quantity()).append(' ')
                        .append(use.kind() == ResourceUse.Kind.RETAIN ? "CURRENT_HOLD_REQUIRED"
                                : completed(step, statuses) ? "PURPOSE_ENDED_NOT_CHANGE_PROOF" : "RESULT_NOT_VERIFIED")
                        .append('\n');
            }
        }
        return text.isEmpty() ? "" : text.append("</resource_purpose_state>").toString();
    }

    private static int add(int a, int b) { return (int) Math.min(Integer.MAX_VALUE, (long) a + b); }
}
