package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 2026-11-12 046: a learned goto's literal axes are historical observations, not current targets.
 * The live sk-e417740e4ad7 body delivered old 2,88,-3 directly in executable goto params.
 * Inspect structured steps, never regex-delete coordinates from prose/evidence or rewrite a vN body.
 * There is no host verification of world-specific locations at these learner boundaries; even a
 * self-declared world/session or "verified" flag cannot authorize static absolute goto parameters.
 * Preserve such originals for review; procedural drafts should bind current observations using
 * ACX's existing input/step references. This is a narrow literal-goto check, not an ACX validator
 * or proof that a caller's dynamic input is fresh (execution retains that responsibility).
 */
public final class LearnedLocationBinding {
    private LearnedLocationBinding() {}

    /** Empty means no literal absolute goto axis was found, not that all location use is verified. */
    public static String problem(String body) {
        if (body == null || body.isBlank()) return "";
        JsonElement root;
        try { root = JsonParser.parseString(body); }
        catch (RuntimeException nonJson) { return ""; } // prose/malformed drafts retain their own validators
        return inspect(root, "body");
    }

    private static String inspect(JsonElement node, String path) {
        if (node.isJsonArray()) {
            int i = 0;
            for (JsonElement child : node.getAsJsonArray()) {
                String why = inspect(child, path + "[" + i++ + "]");
                if (!why.isEmpty()) return why;
            }
        } else if (node.isJsonObject()) {
            JsonObject object = node.getAsJsonObject();
            JsonElement block = object.get("block");
            if (block != null && block.isJsonPrimitive() && block.getAsJsonPrimitive().isString()
                    && "goto".equals(block.getAsString()) && object.has("params")
                    && object.get("params").isJsonObject()) {
                JsonObject params = object.getAsJsonObject("params");
                for (String axis : new String[]{"x", "y", "z"}) {
                    JsonElement value = params.get(axis);
                    if (literalNumber(value)) {
                        return "location-binding-required: " + path + ".params." + axis
                                + " 是历史绝对位置；从当前观测绑定 $input 或本次步骤输出，"
                                + "世界专属地点保留原 world/session 证据并重新核验，不得直接复用坐标";
                    }
                }
            }
            // Only traverse executable structure. Description/evidence may describe old JSON steps.
            for (String key : new String[]{"steps", "params", "then", "else", "branches", "body"}) {
                JsonElement child = object.get(key);
                if (child != null && (child.isJsonObject() || child.isJsonArray())) {
                    String why = inspect(child, path + "." + key);
                    if (!why.isEmpty()) return why;
                }
            }
        }
        return "";
    }

    private static boolean literalNumber(JsonElement value) {
        if (value == null || !value.isJsonPrimitive()) return false;
        var primitive = value.getAsJsonPrimitive();
        if (primitive.isNumber()) return true;
        if (!primitive.isString()) return false;
        try { new java.math.BigDecimal(primitive.getAsString().trim()); return true; }
        catch (NumberFormatException dynamicOrOther) { return false; }
    }
}
