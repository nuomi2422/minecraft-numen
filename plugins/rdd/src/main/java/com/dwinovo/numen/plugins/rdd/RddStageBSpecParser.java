package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.NodeType;
import com.dwinovo.numen.rdd.api.RecurrenceSpec;
import com.dwinovo.numen.rdd.api.SubtaskSpec;
import com.dwinovo.numen.rdd.core.RddChainFactory;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stage-B wire decoding only: pure decompose_goal spec JSON parsing; client async orchestration stays in
 * RddDecomposer. Separate from Stage-A's {@link RddStagePlanParser} because the two wire shapes and
 * consumers differ; {@link RddDecomposer#parse} stays the public seam existing callers/tests use.
 *
 * <p>Log category is kept on {@link RddDecomposer} on purpose: moving pure decoding must not change an
 * observable filter key.
 */
final class RddStageBSpecParser {
    private static final Logger LOG = LoggerFactory.getLogger(RddDecomposer.class);
    private static final Gson GSON = new Gson();

    private RddStageBSpecParser() {}

    static List<SubtaskSpec> parse(String argumentsJson) {
        return parseDetailed(argumentsJson).specs();
    }

    /**
     * 与 {@link #parse} 同一套解码；额外报告被 schema 丢弃的<b>必需</b>（MAIN）步骤，
     * 让发布侧不能把"六步缩成木材前缀"当成功安装。可选/DAILY 的跳过不算必需丢弃。
     */
    record Parsed(List<SubtaskSpec> specs, List<String> skippedRequired) {}

    static Parsed parseDetailed(String argumentsJson) {
        List<SubtaskSpec> out = new ArrayList<>();
        List<String> skippedRequired = new ArrayList<>();
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return new Parsed(out, skippedRequired);
        }
        try {
            JsonObject root = GSON.fromJson(argumentsJson, JsonObject.class);
            if (root == null || !root.has("subtasks") || !root.get("subtasks").isJsonArray()) {
                return new Parsed(out, skippedRequired);
            }
            JsonArray arr = root.getAsJsonArray("subtasks");
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) {
                    continue;
                }
                JsonObject candidate = el.getAsJsonObject();
                SubtaskSpec spec = parseOne(candidate);
                if (spec == null && candidate.has("condition") && candidate.get("condition").isJsonObject()
                        && candidate.getAsJsonObject("condition").has("slots")) {
                    LOG.warn("[rdd] 显式 slots 步骤非法，整批拒绝，不能丢掉装备要求后发布前缀");
                    return new Parsed(List.of(), List.of("显式 slots 步骤非法，整批拒绝"));
                }
                if (spec != null) {
                    out.add(spec);
                    if (out.size() > RddChainFactory.MAX_SUBTASKS) {
                        LOG.warn("[rdd] 分解合法步骤超过上限 {}，整批拒绝，不能将截断前缀当完整计划", RddChainFactory.MAX_SUBTASKS);
                        return new Parsed(List.of(), List.of("合法步骤超过上限"));
                    }
                } else if (requiredStep(candidate)) {
                    skippedRequired.add(describe(candidate));
                }
            }
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 分解 JSON 解析失败: {}", ex.toString());
            return new Parsed(List.of(), List.of("分解 JSON 解析失败: " + ex));
        }
        return new Parsed(out, skippedRequired);
    }

    /** Missing/default node_type = MAIN; explicit DAILY/SIDE are optional trims, not required drops. */
    private static boolean requiredStep(JsonObject candidate) {
        NodeType type = parseNodeType(candidate);
        return type == null || type == NodeType.MAIN;
    }

    private static String describe(JsonObject candidate) {
        String d = candidate.has("description") ? candidate.get("description").getAsString() : "";
        return d == null || d.isBlank() ? "(未命名必需步骤)" : d;
    }

    /**
     * 单条规格：description 非空、condition 含 asset_key（+可选非负 minimum）。非法返回 null。
     *
     * <p>2026-10-07 用户裁决（单驾驶员）：规划器<b>不给身体动作</b>，body 一律忽略（传 null）。
     * 身体只有一个驾驶员（执行 AI），派活是它的事。
     */
    private static SubtaskSpec parseOne(JsonObject o) {
        String description = o.has("description") ? o.get("description").getAsString() : null;
        if (description == null || description.isBlank()) {
            return null;
        }
        JsonObject cond = o.has("condition") && o.get("condition").isJsonObject()
                ? o.getAsJsonObject("condition") : null;
        if (cond == null) {
            return null;
        }
        if (cond.has("slots") && (!cond.has("type") || !"equipment".equals(cond.get("type").getAsString()))) {
            return null;
        }
        NodeType nodeType = parseNodeType(o);
        if (nodeType == null) return null;
        RecurrenceSpec recurrence = parseRecurrence(o);
        if (o.has("recurrence") && recurrence == null) return null;
        if (cond.has("type") && !"inventory".equals(cond.get("type").getAsString())) {
            Map<String, Object> fact = new LinkedHashMap<>();
            for (var entry : cond.entrySet()) fact.put(entry.getKey(), toPlain(entry.getValue()));
            return com.dwinovo.numen.rdd.core.WorldFactConditions.valid(fact)
                    ? new SubtaskSpec(description, fact, null, nodeType, recurrence) : null;
        }
        String assetKey = cond.has("asset_key") ? cond.get("asset_key").getAsString() : null;
        // 资产键形状不可执行(裸键/占位如 goal/大写) 永不匹配背包键 -> 判不可用丢弃，
        // 宁缺毋滥，不让"合法 JSON 但跑不动"的死条件进任务链造成假卡死。
        String group = cond.has("group") ? cond.get("group").getAsString() : null;
        if (group != null ? !com.dwinovo.numen.rdd.core.InventoryGroups.known(group) || assetKey != null : !RddKeys.usable(assetKey)) {
            return null;
        }
        Map<String, Object> condition = new LinkedHashMap<>();
        condition.put(group == null ? "asset_key" : "group", group == null ? assetKey : group);
        if (cond.has("minimum")) {
            int minimum = cond.get("minimum").getAsInt();
            if (minimum < 0 || group != null && minimum == 0
                    || cond.get("minimum").getAsDouble() != minimum) {
                return null;
            }
            condition.put("minimum", minimum);
        }
        if (group != null && !condition.containsKey("minimum")) condition.put("minimum", 1);
        // 2026-11-03 审计：旧 parseOne 只保留 asset_key/group/minimum/optional，
        // 把显式 mode=acquire 静默降成 hold → “已有就不算新增”语义丢失。未知 mode 直接丢弃该步。
        if (cond.has("mode")) {
            if (!cond.get("mode").isJsonPrimitive()) return null;
            String mode = cond.get("mode").getAsString();
            if (!"hold".equals(mode) && !"acquire".equals(mode)) return null;
            if (group != null && "acquire".equals(mode)) return null;   // group 无增量实现
            condition.put("mode", mode);
        }
        if (cond.has("optional") && cond.get("optional").isJsonPrimitive()
                && cond.getAsJsonPrimitive("optional").isBoolean())
            condition.put("optional", cond.get("optional").getAsBoolean());
        return new SubtaskSpec(description, condition, null, nodeType, recurrence);
    }

    /** Optional node_type; null = invalid (caller drops), missing = MAIN. */
    private static NodeType parseNodeType(JsonObject o) {
        if (!o.has("node_type") || o.get("node_type").isJsonNull()) return NodeType.MAIN;
        if (!o.get("node_type").isJsonPrimitive()) return null;
        try { return NodeType.valueOf(o.get("node_type").getAsString().trim().toUpperCase()); }
        catch (RuntimeException ignored) { return null; }
    }

    /** Optional recurrence object; null when absent or invalid. */
    private static RecurrenceSpec parseRecurrence(JsonObject o) {
        if (!o.has("recurrence") || o.get("recurrence").isJsonNull()) return null;
        if (!o.get("recurrence").isJsonObject()) return null;
        JsonObject r = o.getAsJsonObject("recurrence");
        int interval = r.has("intervalGameDays") ? r.get("intervalGameDays").getAsInt() : 1;
        if (interval <= 0) return null;
        RecurrenceSpec.MissedPolicy policy = RecurrenceSpec.MissedPolicy.SKIP;
        if (r.has("missedPolicy")) {
            try { policy = RecurrenceSpec.MissedPolicy.valueOf(r.get("missedPolicy").getAsString().trim().toUpperCase()); }
            catch (RuntimeException ignored) { return null; }
        }
        boolean farmGate = r.has("requiresFarmBuilt") && r.get("requiresFarmBuilt").getAsBoolean();
        return new RecurrenceSpec(interval, policy, farmGate);
    }

    /** Gson JsonElement → 纯 JVM 值（String/Number/Boolean/null/Map/List）。 */
    private static Object toPlain(JsonElement e) {
        if (e == null || e.isJsonNull()) {
            return null;
        }
        if (e.isJsonPrimitive()) {
            var p = e.getAsJsonPrimitive();
            if (p.isNumber()) {
                double value = p.getAsDouble();
                if (value == (int) value) return (int) value;
                return value;
            }
            if (p.isBoolean()) {
                return p.getAsBoolean();
            }
            return p.getAsString();
        }
        if (e.isJsonObject()) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (var entry : e.getAsJsonObject().entrySet()) {
                m.put(entry.getKey(), toPlain(entry.getValue()));
            }
            return m;
        }
        if (e.isJsonArray()) {
            List<Object> l = new ArrayList<>();
            for (JsonElement el : e.getAsJsonArray()) {
                l.add(toPlain(el));
            }
            return l;
        }
        return e.toString();
    }
}
