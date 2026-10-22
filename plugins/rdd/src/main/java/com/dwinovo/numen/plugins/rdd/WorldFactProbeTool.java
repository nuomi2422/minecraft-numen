package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.core.WorldFactConditions;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 只读世界事实探针（验证 / 诊断用）：把一段 condition JSON 当场喂给
 * {@link RddWorldFacts#matches}，返回 schema 是否合法、此刻是否命中。
 * <b>不改任何状态</b>：不派工、不碰任务链、不写世界。
 *
 * <p>存在理由：新增世界事实类型后，要把“能不能硬判”从“等规划器生成一个用它做条件的二级”
 * 里解放出来——直接传条件当场核对，便于逐组实机验证。
 */
final class WorldFactProbeTool implements NumenTool {

    private static final Gson GSON = new Gson();

    @Override
    public String name() {
        return "world_fact_probe";
    }

    @Override
    public String description() {
        return "Read-only diagnostic: evaluate ONE world-fact condition against the live world right now and "
                + "report {valid, matched}. Pass 'condition' as a JSON object string, e.g. "
                + "{\"type\":\"block_mined\",\"block\":\"minecraft:stone\",\"minimum\":1} or "
                + "{\"type\":\"y_below\",\"y\":0}. Does not change any state and dispatches no body action.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("condition", "Condition JSON object string to evaluate now (see world fact types).")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        try {
            String raw = args != null && args.has("condition") && !args.get("condition").isJsonNull()
                    ? args.get("condition").getAsString() : null;
            if (raw == null || raw.isBlank()) {
                reply.accept(TaskResult.fail("world_fact_probe needs a 'condition' JSON object string").toJson());
                return;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> condition = GSON.fromJson(raw, Map.class);
            if (condition == null) {
                reply.accept(TaskResult.fail("condition must be a JSON object").toJson());
                return;
            }
            // ★ 2026-10-22：形状合法 ≠ 判得出来。#tag 在注册表里不存在时判定恒假而
            // valid 仍为 true —— 模型会把「判不出来」当成「世界没满足」，清怪/巡场/重派
            // 一百次也没用（RL-18 同族）。这里把两种状态分开报。
            boolean shape = WorldFactConditions.valid(condition);
            java.util.List<String> unknownTags = shape
                    ? RddWorldFacts.unresolvedTags(self, condition) : java.util.List.of();
            boolean valid = shape && unknownTags.isEmpty();
            boolean matched = valid && RddWorldFacts.matches(self, condition);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("type", String.valueOf(condition.get("type")));
            out.put("valid", valid);
            out.put("matched", matched);
            if (!unknownTags.isEmpty()) {
                out.put("unknown_tags", unknownTags);
                out.put("reason", "这些 #tag 在本世界注册表里不存在：条件永远判不出来，"
                        + "这不是世界没满足。改用具体 ID（例如 minecraft:cat / minecraft:villager）重新表达条件。");
            }
            out.put("condition", condition);
            String message = !shape ? "condition is malformed"
                    : !unknownTags.isEmpty() ? "condition references a tag this world does not have"
                    : matched ? "world fact matches now" : "world fact does not match now";
            reply.accept(TaskResult.ok(message, out).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("invalid condition: " + ex.getMessage()).toJson());
        }
    }
}
