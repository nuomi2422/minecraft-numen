package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.RationaleContext;
import com.google.gson.JsonObject;

/** Shared parser for planner-produced rationale; absent fields stay absent, never invented. */
final class RddRationale {
    private RddRationale() {}

    static RationaleContext.Entry from(JsonObject args, String defaultSource) {
        String why = str(args, "reason");
        String risks = str(args, "risks");
        String source = str(args, "rationale_source");
        String conditions = str(args, "conditions");
        return new RationaleContext.Entry(why, risks, source.isBlank() ? defaultSource : source, conditions);
    }

    private static String str(JsonObject args, String key) {
        return args != null && args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString().trim() : "";
    }
}
