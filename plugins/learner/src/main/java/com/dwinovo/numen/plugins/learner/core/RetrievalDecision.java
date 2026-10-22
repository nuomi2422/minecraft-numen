package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * G6b：复盘前的「先看一眼已经有什么」。
 *
 * <p><b>为什么单独成 record 而不塞进 {@link Verdict} 的字段里</b>：它回答的是
 * 「这条笔记跟库里已有的东西是什么关系」，不是「这条笔记本身写了什么」。
 * 混进去以后，下游要判断「这条经验可信吗」时还得先剥掉检索信息。
 *
 * <h2>判据（Z1–Z3）</h2>
 * <ul>
 *   <li><b>Z1</b>：必须有 hits / decision / reason 三样；缺一样就不是合法决策。</li>
 *   <li><b>Z2</b>：REUSE/REVISE 必须带 entryRef，且该 ref <b>必须出现在本次 hits 里</b>
 *       （不许引用一条没检索到的条目）；NEW 必须给理由，且<b>有高相似命中时不许 NEW</b>。</li>
 *   <li><b>Z3</b>：同主题第二次进来，库里那条会被标成 highSimilarity ⇒ NEW 被 Z2 挡住，
 *       只能落 REUSE/REVISE。</li>
 * </ul>
 *
 * <p><b>坏 JSON 不猜</b>：解析不出来返回 {@code null}，由调用方按「没给决策」处理（Z2 会拒）。
 */
public record RetrievalDecision(List<Hit> hits, Kind decision, String reason) {

    /** 决策三态。 */
    public enum Kind {
        REUSE,
        REVISE,
        NEW;

        public static Kind parse(String raw) {
            if (raw == null) return null;
            String v = raw.trim().toUpperCase(Locale.ROOT);
            for (Kind k : values()) {
                if (k.name().equals(v)) return k;
            }
            return null;
        }
    }

    /**
     * 一条命中。
     *
     * @param highSimilarity 是否「同一件事」级别 —— 由检索侧判（同标题或同触发词）。
     *                       它是 Z2 里「不许 NEW」的唯一依据，**不能由 LLM 自己填**。
     */
    public record Hit(String entryRef, String title, String why, boolean highSimilarity) {
        public Hit {
            entryRef = entryRef == null ? "" : entryRef.trim();
            title = title == null ? "" : title.trim();
            why = why == null ? "" : why.trim();
        }
    }

    public RetrievalDecision {
        hits = hits == null ? List.of() : List.copyOf(hits);
        reason = reason == null ? "" : reason.trim();
    }

    /** 校验结论：{@code null} = 合法；否则是给人看的拒因。 */
    public String problem() {
        if (decision == null) {
            return "缺 decision（REUSE/REVISE/NEW 三选一）";
        }
        if (reason.isBlank()) {
            return "缺 reason：决策必须写清为什么";
        }
        switch (decision) {
            case REUSE, REVISE -> {
                String ref = hits.stream().map(Hit::entryRef).filter(r -> !r.isBlank())
                        .findFirst().orElse("");
                if (ref.isBlank()) {
                    return decision + " 必须指向一条已有条目，但 hits 是空的";
                }
                // 调用方会把「被引用的 ref」也放进 hits（见 LearnerReviewTool），
                // 所以这里只要求 hits 非空 + decision 带 ref —— ref 具体哪个由 Z2 的调用点核。
            }
            case NEW -> {
                if (hits.stream().anyMatch(Hit::highSimilarity)) {
                    return "有高相似命中却判 NEW：同主题不许再新建（Z3）";
                }
            }
        }
        return null;
    }

    /** 是否合法（{@link #problem()} == null）。 */
    public boolean valid() {
        return problem() == null;
    }

    /** 从 LLM 的 JSON 里读 {@code retrieval} 段；没有这一段 → null（老格式，由闸门决定收不收）。 */
    public static RetrievalDecision parse(JsonObject obj) {
        if (obj == null || !obj.has("retrieval") || !obj.get("retrieval").isJsonObject()) {
            return null;
        }
        JsonObject r = obj.getAsJsonObject("retrieval");
        List<Hit> hits = new ArrayList<>();
        if (r.has("hits") && r.get("hits").isJsonArray()) {
            JsonArray arr = r.getAsJsonArray("hits");
            for (JsonElement e : arr) {
                if (!e.isJsonObject()) continue;
                JsonObject h = e.getAsJsonObject();
                hits.add(new Hit(
                        str(h, "entry_ref"),
                        str(h, "title"),
                        str(h, "why"),
                        bool(h, "high_similarity")));
            }
        }
        return new RetrievalDecision(hits, Kind.parse(str(r, "decision")), str(r, "reason"));
    }

    private static String str(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return "";
        return o.get(key).getAsString();
    }

    private static boolean bool(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonPrimitive()) return false;
        try {
            return o.get(key).getAsBoolean();
        } catch (RuntimeException ignored) {
            return false;
        }
    }
}
