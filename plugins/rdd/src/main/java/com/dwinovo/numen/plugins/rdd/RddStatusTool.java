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
        final ChainProjection projection = projectChain(chain0);
        // This is a point-in-time status sample, not a lock or authority to mutate.
        // Runtime replacement can still occur after the final identity check; the
        // mutation consumer must bind chain_instance_id as well as the versions.
        if (projection.completed()) {
            if (RddPlugin.runtime(uuid) != runtime) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail("RDD runtime changed during status snapshot; retry").toJson());
                return;
            }
            reply.accept(com.dwinovo.numen.task.TaskResult.ok("RDD task chain is complete", projection.data()).toJson());
            return;
        }
        var current = projection.current();
        if (RddPlugin.runtime(uuid) != runtime) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("RDD runtime changed during status snapshot; retry").toJson());
            return;
        }
        var inventory = RddDetector.countInventory(companion);
        var groups = Map.of("food", com.dwinovo.numen.rdd.core.InventoryGroups.count("food", inventory),
                "wood", com.dwinovo.numen.rdd.core.InventoryGroups.count("wood", inventory),
                "blocks", com.dwinovo.numen.rdd.core.InventoryGroups.count("blocks", inventory));
        if (current == null) {
            Map<String, Object> awaiting = new java.util.LinkedHashMap<>(projection.data());
            awaiting.put("condition_groups", groups);
            if (RddPlugin.runtime(uuid) != runtime) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail("RDD runtime changed during status snapshot; retry").toJson());
                return;
            }
            reply.accept(com.dwinovo.numen.task.TaskResult.ok("RDD task chain is awaiting expansion", awaiting).toJson());
            return;
        }
        Map<String, Object> out = new java.util.LinkedHashMap<>(projection.data());
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
        if (RddPlugin.runtime(uuid) != runtime) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("RDD runtime changed during status snapshot; retry").toJson());
            return;
        }
        reply.accept(com.dwinovo.numen.task.TaskResult.ok("RDD task chain is active", out).toJson());
    }

    record ChainProjection(Map<String, Object> data, Subtask current, boolean completed) {}

    /** Capture only chain-owned immutable data under the monitor; providers and callbacks run outside. */
    static ChainProjection projectChain(com.dwinovo.numen.rdd.core.TaskChain chain) {
        synchronized (chain) {
            Subtask current = chain.currentSubtask();
            boolean complete = current != null && chain.primaryStatus() == PrimaryGoalStatus.COMPLETED
                    && chain.currentSubtaskStatus() == SubtaskStatus.COMPLETED;
            Map<String, Object> fields = new java.util.LinkedHashMap<>(generationFields(chain));
            fields.put("active", !complete);
            if (complete) fields.put("completed", true);
            fields.put("goal", chain.goal().id());
            fields.put("primary_goal", chain.currentPrimary().id());
            fields.put("primary_status", chain.primaryStatus().name());
            // Read-only view of stages settled by fact, from the same snapshot. It lets a
            // caller prove "nothing was settled" before/after a refused request instead of
            // inferring it from the absence of a change event.
            fields.put("settled_stages", Map.copyOf(chain.settledStages()));
            if (current != null) {
                fields.put("subtask", current.id());
                fields.put("subtask_status", chain.currentSubtaskStatus().name());
            }
            return new ChainProjection(Map.copyOf(fields), current, complete);
        }
    }

    /** Generation values are sampled from the same chain under its monitor. */
    static Map<String, Object> generationFields(com.dwinovo.numen.rdd.core.TaskChain chain) {
        if (chain == null) return Map.of();
        synchronized (chain) {
            String id = chain.chainInstanceId();
            if (id == null || id.isBlank()) return Map.of();
            return Map.of("chain_instance_id", id, "state_version", chain.stateVersion(),
                    "plan_revision", chain.planRevision());
        }
    }
}
