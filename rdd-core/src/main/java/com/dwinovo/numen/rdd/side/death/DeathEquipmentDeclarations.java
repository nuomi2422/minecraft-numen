package com.dwinovo.numen.rdd.side.death;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.core.WorldFactConditions;
import com.dwinovo.numen.rdd.policy.AssetSemantics;
import com.google.gson.Gson;
import java.util.*;
import static com.dwinovo.numen.rdd.side.death.DeathPreparation.MAX_JSON;
import static com.dwinovo.numen.rdd.side.death.DeathPreparation.MAX_TARGETS;

/**
 * Pure read-only equipment declaration selection for DeathPreparation's original entry points.
 * Separates reached typed requirements and waived review candidates from side lifecycle writes.
 * Not a second obligation store: all inputs remain the existing chain and planner semantics;
 * returned candidates are not current wearing evidence and never imply automatic restoration.
 */
final class DeathEquipmentDeclarations {
    private static final Gson JSON = new Gson();
    private DeathEquipmentDeclarations() {}

    record Selection(String scope, List<Map<String, Object>> targets, String reason) {}
    private static Selection unknownSelection(String reason) { return new Selection("UNKNOWN", List.of(), reason); }

    /** Stop at the real current subtask, not the end of its primary's future plan. */
    static Selection select(TaskChain chain, Map<String, AssetSemantics> semantics,
                                    Map<String, String> wearSlots) {
        if (chain == null || chain.goal() == null || chain.currentPrimary() == null)
            return unknownSelection("recovery_chain_unavailable");
        Map<String, Map<String, Object>> selected = new LinkedHashMap<>();
        Set<String> reached = new LinkedHashSet<>(), later = new LinkedHashSet<>();
        boolean boundary = false;
        for (PrimaryGoal primary : chain.goal().primaryGoals()) {
            boolean current = primary.id().equals(chain.currentPrimary().id());
            for (Subtask sub : primary.subtasks()) {
                if (boundary) { declarationKey(sub.condition()).ifPresent(later::add); continue; }
                Map<String, Object> c = sub.condition();
                if (chain.subtaskStatuses().get(sub.id()) != SubtaskStatus.SKIPPED
                        && !Boolean.TRUE.equals(c.get("optional"))) {
                    declarationKey(c).ifPresent(reached::add);
                    if ("equipment".equals(c.get("type"))) {
                        if (!WorldFactConditions.valid(c)) return unknownSelection("invalid_equipment_declaration");
                        selected.put(wearKey(c), c);
                    } else if (c.get("asset_key") instanceof String key && wearSlots != null && wearSlots.containsKey(key)) {
                        Object raw = c.getOrDefault("minimum", 1);
                        if (!(raw instanceof Number n) || n.doubleValue() != n.intValue() || n.intValue() <= 0)
                            return unknownSelection("invalid_equipment_quantity");
                        addDeclared(selected, key, n.intValue(), wearSlots);
                    }
                }
                if (current && chain.currentSubtask() != null && sub.id().equals(chain.currentSubtask().id())) boundary = true;
            }
            if (current) boundary = true;
        }
        for (PrimaryGoal primary : chain.goal().primaryGoals()) {
            boolean current = primary.id().equals(chain.currentPrimary().id());
            for (AssetRequirement requirement : primary.waitFor()) {
                // Entry declarations cannot smuggle in the current primary's later equipment output.
                if (!current || reached.contains(requirement.assetKey()) || !later.contains(requirement.assetKey())) {
                    addDeclared(selected, requirement.assetKey(), requirement.minimum(), wearSlots);
                    reached.add(requirement.assetKey());
                }
            }
            if (current) break;
        }
        if (semantics != null) for (AssetSemantics s : semantics.values()) {
            if (s == null || !reached.contains(s.assetKey()) || s.nature() != AssetSemantics.Nature.EQUIPMENT
                    || !s.important() || !chain.goal().id().equals(s.servesGoal())) continue;
            if (!s.requiredCapability().matches("[1-9][0-9]{0,3}")) return unknownSelection("equipment_quantity_not_declared");
            int minimum = Integer.parseInt(s.requiredCapability());
            String slot = wearSlots == null ? null : wearSlots.get(s.assetKey());
            if (slot == null) {
                selected.put("stock:" + s.assetKey(), Map.of("asset_key", s.assetKey(), "minimum", minimum, "mode", "hold"));
                continue;
            }
            var wear = Map.<String, Object>of("type", "equipment", "item", s.assetKey(), "slot", slot);
            selected.putIfAbsent(wearKey(wear), wear);
            if (minimum > 1) selected.put("stock:" + s.assetKey(), Map.of("asset_key", s.assetKey(), "minimum", minimum, "mode", "hold"));
        }
        if (selected.size() > MAX_TARGETS || JSON.toJson(selected.values()).length() > MAX_JSON)
            return unknownSelection("equipment_declarations_over_budget");
        return new Selection(selected.isEmpty() ? "NONE" : "DECLARED", List.copyOf(selected.values()),
                selected.isEmpty() ? "no_applicable_equipment_obligation" : "equipment_recheck_required");
    }

    private static Optional<String> declarationKey(Map<String, Object> c) {
        Object raw = "equipment".equals(c.get("type")) ? c.get("item") : c.get("asset_key");
        return raw instanceof String s ? Optional.of(s) : Optional.empty();
    }

    private static String wearKey(Map<String, Object> c) {
        String slot = String.valueOf(c.getOrDefault("slot", "any"));
        slot = switch (slot) { case "boots" -> "feet"; case "leggings" -> "legs";
            case "chestplate" -> "chest"; case "helmet" -> "head"; default -> slot; };
        return "wear:" + slot + ("any".equals(slot) ? ":" + c.get("item") : "") + ":" + c.getOrDefault("dimension", "");
    }
    private static void addDeclared(Map<String, Map<String, Object>> selected, String key, int minimum,
                                    Map<String, String> slots) {
        String slot = slots == null ? null : slots.get(key);
        if (slot == null) return;
        var wear = Map.<String, Object>of("type", "equipment", "item", key, "slot", slot);
        selected.put(wearKey(wear), wear);
        if (minimum > 1) selected.put("stock:" + key,
                Map.of("asset_key", key, "minimum", minimum, "mode", "hold"));
    }

    static Map<String, Map<String, Object>> reviewCandidates(TaskChain chain) {
        var candidates = new LinkedHashMap<String, Map<String, Object>>();
        for (PrimaryGoal primary : chain.goal().primaryGoals()) {
            for (Subtask sub : primary.subtasks()) {
                if (chain.hasStageWaiver(primary.id()) && chain.subtaskStatuses().get(sub.id()) == SubtaskStatus.SKIPPED
                        && "equipment".equals(sub.condition().get("type")) && WorldFactConditions.valid(sub.condition())
                        && !Boolean.TRUE.equals(sub.condition().get("optional"))) candidates.put(sub.id(), sub.condition());
                if (primary.id().equals(chain.currentPrimary().id()) && chain.currentSubtask() != null
                        && sub.id().equals(chain.currentSubtask().id())) break;
            }
            if (primary.id().equals(chain.currentPrimary().id())) break;
        }
        return candidates;
    }
}
