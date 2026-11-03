package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/** Small diagnostic/status tool for the RDD host adapter. */
final class RddStatusTool implements NumenTool {
    @Override public String name() { return "rdd_status"; }
    @Override public String description() { return "Read the RDD asset task-chain status. Use rdd_assets for reusable bases, locations, machines and entity sightings. If the current optional food step is unavailable, use rdd_skip_optional to preserve progress and continue; do not replace the whole chain. For an advancement condition, verification_status distinguishes MATCHED, NOT_MATCHED, INVALID, and NOT_VERIFIABLE. condition_matches is absent if invalid or unavailable, not false; it is also absent for advancement+dimension because the legacy detector ignores that dimension. Verification is diagnostic only and does not block execution or failure handling. Reading advancement progress may initialize a missing player progress entry in memory, but does not award progress or rewards."; }
    @Override public Map<String, Object> parameterSchema() { return Schema.none(); }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        UUID uuid = companion.getUUID();
        if (RddPlugin.decomposing(uuid)) {
            reply.accept(com.dwinovo.numen.task.TaskResult.ok("RDD is decomposing the goal",
                    Map.of("active", false, "decomposing", true)).toJson());
            return;
        }
        RddRuntime runtime = RddPlugin.runtime(uuid);
        if (runtime == null) {
            reply.accept(com.dwinovo.numen.task.TaskResult.ok("RDD has no active task chain", Map.of("active", false)).toJson());
            return;
        }
        var chain0 = runtime.chain();
        // 2026-11-03：末级完成后链仍报 active=true，面板/AI 会以为还有活；这里明确终态。
        if (chain0.primaryStatus() == PrimaryGoalStatus.COMPLETED
                && chain0.currentSubtaskStatus() == SubtaskStatus.COMPLETED) {
            reply.accept(com.dwinovo.numen.task.TaskResult.ok("RDD task chain is complete", Map.of(
                    "active", false, "completed", true,
                    "goal", chain0.goal().id(),
                    "primary_goal", chain0.currentPrimary().id(),
                    "primary_status", "COMPLETED",
                    "subtask", chain0.currentSubtask().id(),
                    "subtask_status", "COMPLETED")).toJson());
            return;
        }
        var current = runtime.chain().currentSubtask();
        var inventory = RddDetector.countInventory(companion);
        var groups = Map.of("food", com.dwinovo.numen.rdd.core.InventoryGroups.count("food", inventory),
                "wood", com.dwinovo.numen.rdd.core.InventoryGroups.count("wood", inventory),
                "blocks", com.dwinovo.numen.rdd.core.InventoryGroups.count("blocks", inventory));
        if (current == null) {
            reply.accept(com.dwinovo.numen.task.TaskResult.ok("RDD task chain is awaiting expansion", Map.of(
                    "active", true, "goal", runtime.chain().goal().id(),
                    "primary_goal", runtime.chain().currentPrimary().id(),
                    "primary_status", runtime.chain().primaryStatus().name(), "condition_groups", groups)).toJson());
            return;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("active", true);
        out.put("goal", runtime.chain().goal().id());
        out.put("primary_goal", runtime.chain().currentPrimary().id());
        out.put("primary_status", runtime.chain().primaryStatus().name());
        out.put("subtask", current.id());
        out.put("subtask_status", runtime.chain().currentSubtaskStatus().name());
        out.put("condition_groups", groups);
        out.put("condition", current.condition());
        if ("advancement".equals(current.condition().get("type"))) {
            var state = RddWorldFacts.advancementResult(companion, current.condition());
            out.put("verification_status", state.name());
            // Diagnostic honors dimension; the legacy detector does not. Never
            // present the stronger result as if it were the detector's decision.
            if (current.condition().containsKey("dimension")) {
                out.put("verification_scope", "advancement_and_dimension");
                out.put("execution_note", "legacy detector ignores the dimension field for advancement; "
                        + "verification_status is diagnostic, not its execution verdict");
            } else {
                out.put("verification_scope", "advancement");
                // Unknown / invalid must not be serialized as a factual false.
                switch (state) {
                    case MATCHED -> out.put("condition_matches", true);
                    case NOT_MATCHED -> out.put("condition_matches", false);
                    default -> { }
                }
            }
        } else {
            out.put("condition_matches", RddDetector.conditionMatches(companion, current, inventory));
        }
        out.put("assets", runtime.assets().snapshot().size());
        reply.accept(com.dwinovo.numen.task.TaskResult.ok("RDD task chain is active", out).toJson());
    }
}
