package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Creates the first explicit RDD chain from a bounded tool request. */
final class RddSubmitTool implements NumenTool {
    private static final Gson GSON = new Gson();

    @Override public String name() { return "rdd_submit"; }
    @Override public String description() {
        return "Submit a bounded RDD asset task chain. Required: goal, primary_goal, subtask. "
                + "The subtask uses a deterministic condition and is checked by the RDD runtime.";
    }
    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("goal", "Overall goal description.")
                .string("primary_goal", "Current primary goal description.")
                .string("subtask", "Current executable subtask description.")
                .string("asset_key", "Asset key used by the hard-coded observation condition.")
                .integer("minimum", "Minimum observed count required.", 0, Integer.MAX_VALUE)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Input input = GSON.fromJson(args, Input.class);
            if (input == null || blank(input.goal()) || blank(input.primary_goal()) || blank(input.subtask())
                    || blank(input.asset_key()) || input.minimum() < 0) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "rdd_submit requires goal, primary_goal, subtask, asset_key and non-negative minimum").toJson());
                return;
            }
            String primaryId = "primary-" + companion.getUUID();
            String subtaskId = "subtask-" + companion.getUUID();
            var subtask = Subtask.hardCoded(subtaskId, input.subtask(),
                    Map.of("asset_key", input.asset_key(), "minimum", input.minimum()));
            var primary = new com.dwinovo.numen.rdd.api.PrimaryGoal(primaryId, input.primary_goal(), java.util.List.of(subtask));
            var goal = new com.dwinovo.numen.rdd.api.Goal("goal-" + companion.getUUID(), input.goal(), java.util.List.of(primary));
            RddPlugin.bind(companion.getUUID(), goal);
            RddRuntime runtime = RddPlugin.runtime(companion.getUUID());
            runtime.startCurrent();
            reply.accept(com.dwinovo.numen.task.TaskResult.ok("RDD task chain accepted and started",
                    Map.of("goal", goal.id(), "primary_goal", primaryId, "subtask", subtaskId,
                            "detection_mode", "HARD_CODED")).toJson());
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("invalid RDD task chain: " + ex.getMessage()).toJson());
        }
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private record Input(String goal, String primary_goal, String subtask, String asset_key, int minimum) {}
}
