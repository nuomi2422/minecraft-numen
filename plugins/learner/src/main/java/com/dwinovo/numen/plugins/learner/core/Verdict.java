package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 学习者对一条备忘录的判定（v1 = plan-only，不执行）。
 *
 * <p>对应规格 §3 的六个取值。动作是<b>多选</b>：一条备忘录可以同时建议
 * 写经验和调 AC。
 *
 * <p>解析策略：LLM 返回的文本必须能被解析成合法 JSON 才产出判定；
 * <b>解析不出来就返回 null</b>，由调用方明确回报 LLM_UNAVAILABLE/UNPARSEABLE，
 * 绝不伪造一个「看起来合理」的判定（对齐经验库 §11 的验收要求）。
 */
public record Verdict(
        String memoId,
        List<Action> actions,
        double confidence,
        String reasoning,
        String experienceDraft,
        String acScriptDraft,
        List<String> rewrittenQuery
) {

    /** 判定动作。v1 全部是「建议」，由 AI 决定是否执行。 */
    public enum Action {
        WRITE_EXPERIENCE,
        USE_AC,
        USE_CARRIER,
        SELF_COMPILE,
        NO_ACTION,
        NEEDS_HUMAN;

        public static Action parse(String raw) {
            if (raw == null) {
                return null;
            }
            String k = raw.trim().toUpperCase(Locale.ROOT);
            for (Action a : values()) {
                if (a.name().equals(k)) {
                    return a;
                }
            }
            return null;
        }
    }

    private static final Gson GSON = new Gson();

    /**
     * 从 LLM 原始回复里解析出判定；解析失败返回 {@code null}（调用方须如实回报）。
     *
     * <p>容忍模型把 JSON 包在 ```json 代码块里 —— 这在实际调用里非常常见。
     */
    public static Verdict parse(String fallbackMemoId, String rawLlmText) {
        if (rawLlmText == null || rawLlmText.isBlank()) {
            return null;
        }
        String cleaned = stripFence(rawLlmText.trim());
        try {
            JsonElement root = JsonParser.parseString(cleaned);
            if (!root.isJsonObject()) {
                return null;
            }
            JsonObject obj = root.getAsJsonObject();

            // P1 修复（2026-09-29）：memo_id 必须从 JSON 里读。
            // 原实现用调用方传的占位符（批量时是 "?"），导致下游无法把判定
            // 对回具体备忘录，复盘工具只能整批 commit —— 会删掉没被复盘到的条目。
            // 缺失 memo_id 时返回 null（让调用方把这批当未判定处理），不许猜。
            String jsonMemoId = optString(obj, "memo_id");
            if (jsonMemoId.isBlank()) {
                jsonMemoId = fallbackMemoId == null ? "" : fallbackMemoId.trim();
            }
            if (jsonMemoId.isBlank()) {
                return null;
            }
            final String memoId = jsonMemoId;

            List<Action> actions = new ArrayList<>();
            if (obj.has("actions") && obj.get("actions").isJsonArray()) {
                for (JsonElement e : obj.getAsJsonArray("actions")) {
                    if (!e.isJsonPrimitive()) {
                        continue;
                    }
                    Action a = Action.parse(e.getAsString());
                    if (a != null && !actions.contains(a)) {
                        actions.add(a);
                    }
                }
            }
            if (actions.isEmpty()) {
                return null;
            }

            double confidence = 0.0;
            if (obj.has("confidence") && obj.get("confidence").isJsonPrimitive()) {
                try {
                    confidence = obj.get("confidence").getAsDouble();
                } catch (RuntimeException ignored) {
                    confidence = 0.0;
                }
            }
            confidence = Math.max(0.0, Math.min(1.0, confidence));

            String reasoning = optString(obj, "reasoning");
            String expDraft = optString(obj, "experience_draft");
            String acDraft = optString(obj, "ac_script_draft");

            List<String> queries = new ArrayList<>();
            if (obj.has("rewritten_query")) {
                JsonElement q = obj.get("rewritten_query");
                if (q.isJsonArray()) {
                    JsonArray arr = q.getAsJsonArray();
                    for (JsonElement e : arr) {
                        if (e.isJsonPrimitive()) {
                            String v = e.getAsString().trim();
                            if (!v.isBlank()) {
                                queries.add(v);
                            }
                        }
                    }
                } else if (q.isJsonPrimitive()) {
                    String v = q.getAsString().trim();
                    if (!v.isBlank()) {
                        queries.add(v);
                    }
                }
            }

            return new Verdict(memoId, List.copyOf(actions), confidence, reasoning,
                    expDraft, acDraft, List.copyOf(queries));
        } catch (RuntimeException e) {
            // 不是合法 JSON：如实返回 null，让调用方报 UNPARSEABLE
            return null;
        }
    }

    private static String stripFence(String s) {
        if (!s.startsWith("```")) {
            return s;
        }
        int firstNl = s.indexOf('\n');
        if (firstNl < 0) {
            return s;
        }
        int lastFence = s.lastIndexOf("```");
        if (lastFence <= firstNl) {
            return s.substring(firstNl + 1);
        }
        return s.substring(firstNl + 1, lastFence).trim();
    }

    private static String optString(JsonObject obj, String field) {
        if (!obj.has(field) || !obj.get(field).isJsonPrimitive()) {
            return "";
        }
        String v = obj.get(field).getAsString().trim();
        return v == null ? "" : v;
    }

    /** 供日志/监测台使用的紧凑 JSON。 */
    public String toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("memo_id", memoId);
        JsonArray acts = new JsonArray();
        for (Action a : actions) {
            acts.add(a.name());
        }
        o.add("actions", acts);
        o.addProperty("confidence", confidence);
        o.addProperty("reasoning", reasoning);
        o.addProperty("experience_draft", experienceDraft);
        o.addProperty("ac_script_draft", acScriptDraft);
        JsonArray qs = new JsonArray();
        for (String q : rewrittenQuery) {
            qs.add(q);
        }
        o.add("rewritten_query", qs);
        return GSON.toJson(o);
    }

    /** 展示用摘要。 */
    public String summary() {
        return memoId + " -> " + String.join("+", actions.stream().map(Enum::name).toList())
                + " (confidence=" + String.format(Locale.ROOT, "%.2f", confidence) + ")";
    }
}
