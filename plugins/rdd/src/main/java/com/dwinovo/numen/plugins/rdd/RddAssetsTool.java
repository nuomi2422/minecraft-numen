package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Read-only inspection of reusable bases, locations, machines and entity sightings. */
final class RddAssetsTool implements NumenTool {
    @Override public String name() { return "rdd_assets"; }
    @Override public String description() {
        return "Read RDD reusable world assets: bases, structures/locations, machines and notable entity sightings. "
                + "LAZY sightings are historical leads and must be rechecked before use.";
    }
    @Override public Map<String, Object> parameterSchema() { return Schema.none(); }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        var assets = RddAssetContext.worldAssets(RddPlugin.assets(companion.getUUID()));
        reply.accept(com.dwinovo.numen.task.TaskResult.ok("RDD reusable world assets",
                Map.of("count", assets.size(), "assets", assets)).toJson());
    }
}
