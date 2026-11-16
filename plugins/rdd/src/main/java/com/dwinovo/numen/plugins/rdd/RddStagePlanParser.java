package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetRequirement;
import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.core.RddChainFactory;
import com.dwinovo.numen.rdd.core.WorldFactConditions;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stage-A wire decoding only; host orchestration and planning policy stay in their owners. */
final class RddStagePlanParser {
    private static final Gson GSON = new Gson();

    private RddStagePlanParser() {}

    static List<PrimarySpec> parse(String argumentsJson) {
        List<PrimarySpec> out = new ArrayList<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return out;
        }
        try {
            JsonObject root = GSON.fromJson(argumentsJson, JsonObject.class);
            if (root == null || !root.has("stages") || !root.get("stages").isJsonArray()) {
                return out;
            }
            JsonArray arr = root.getAsJsonArray("stages");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject o = el.getAsJsonObject();
                // 即使无主题或超过阶段上限，显式坏义务也不能被静默截掉。
                Map<String, String> equipmentTarget = parseEquipmentTarget(o.get("equipment_target"));
                if (out.size() >= RddChainFactory.MAX_STAGES) {
                    continue;
                }
                String theme = o.has("theme") ? o.get("theme").getAsString() : null;
                if (theme == null || theme.isBlank()) {
                    continue;
                }
                out.add(new PrimarySpec(theme.strip(), parseWaitFor(o.get("wait_for")), equipmentTarget));
            }
        } catch (RuntimeException ex) {
            return List.of();
        }
        return out;
    }

    /** 缺字段 = 无新义务；显式值只转换字符串，四槽键与 ID/tag 规则由 core 的 slots4 schema 统一校验。 */
    private static Map<String, String> parseEquipmentTarget(JsonElement raw) {
        if (raw == null) return null;
        if (!raw.isJsonObject()) throw new IllegalArgumentException("equipment_target must be a four-slot object");
        Map<String, String> target = new LinkedHashMap<>();
        for (var entry : raw.getAsJsonObject().entrySet()) {
            JsonElement value = entry.getValue();
            if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("equipment_target values must be ID/tag strings");
            }
            target.put(entry.getKey(), value.getAsString());
        }
        if (!WorldFactConditions.valid(Map.of("type", "equipment", "slots", target))) {
            throw new IllegalArgumentException("invalid equipment_target slots");
        }
        return target;
    }

    /** wait_for 可空；逐条取可用(资产键形状) + 非负 minimum（缺省 1），坏条丢弃整条门不进列表。 */
    private static List<AssetRequirement> parseWaitFor(JsonElement wfEl) {
        if (wfEl == null || !wfEl.isJsonArray()) {
            return List.of();
        }
        List<AssetRequirement> wf = new ArrayList<>();
        for (JsonElement el : wfEl.getAsJsonArray()) {
            if (!el.isJsonObject()) {
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            String key = o.has("asset_key") ? o.get("asset_key").getAsString() : null;
            if (!RddKeys.usable(key)) {
                continue;
            }
            int minimum = 1;
            if (o.has("minimum")) {
                int m = o.get("minimum").getAsInt();
                if (m <= 0) {
                    continue; // 非法数量：整条门不要，别拿 m=0/-1 当"无需持有"
                }
                minimum = m;
            }
            wf.add(new AssetRequirement(key, minimum));
        }
        return wf;
    }

}
