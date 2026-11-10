package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** Read-only inspection of reusable bases, locations, machines and entity sightings. */
final class RddAssetsTool implements NumenTool {

    @Override public String name() { return "rdd_assets"; }
    @Override public String description() {
        return "Read RDD reusable world assets: bases, structures/locations, machines and notable entity sightings. "
                + "LAZY sightings are historical leads and must be rechecked before use. "
                + "Omit arguments for legacy newest entries; catalog supports offset/limit, detail requires exact asset_id. "
                + "No refresh or cross-world search. Oversized details fail too_large; nested field paging is not supported.";
    }
    @Override public Map<String, Object> parameterSchema() {
        return Schema.object().optionalEnum("action", "Omit for legacy newest view; catalog pages; detail exact ID", "catalog", "detail")
                .optionalString("asset_id", "Exact world asset ID for detail; only this companion registry")
                .optionalInteger("offset", "Catalog offset; pages reflect current snapshot", 0, Integer.MAX_VALUE)
                .optionalInteger("limit", "Catalog page size", 1, 40).build();
    }

    static String dispatch(JsonObject args, AssetRegistry registry) {
        return RddAssetReadView.dispatch(args, registry);
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        reply.accept(dispatch(args, RddPlugin.assets(companion.getUUID())));
    }
}
