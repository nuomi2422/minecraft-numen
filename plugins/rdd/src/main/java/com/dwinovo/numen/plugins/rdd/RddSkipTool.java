package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Lets Numen challenge an unreachable optional food step without replacing the whole chain. */
final class RddSkipTool implements NumenTool {
    @Override public String name() { return "rdd_skip_optional"; }
    @Override public String description() {
        return "Skip the current optional food subtask (carrot/potato/beetroot/baked_potato/bread) when it is unavailable or unreachable, preserving completed progress and continuing the mainline. Refuses equipment, mining, Nether, End, and architecture steps.";
    }
    @Override public Map<String, Object> parameterSchema() {
        return Schema.object().optionalString("reason", "Visible reason for skipping this optional food step.").build();
    }
    @Override public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        String reason = args != null && args.has("reason") ? args.get("reason").getAsString() : "optional food unavailable";
        reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                RddPlugin.skipOptionalCurrent(companion.getUUID(), reason), Map.of()).toJson());
    }
}
