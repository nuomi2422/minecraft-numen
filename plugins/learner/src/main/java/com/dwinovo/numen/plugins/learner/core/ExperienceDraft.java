package com.dwinovo.numen.plugins.learner.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 学习者七字段 → <b>经验库条目形状</b>的映射（{@code 60} 号 §四 B2：这条映射代码从来没写过）。
 *
 * <p><b>为什么是「产 JSON」而不是「产 ExperienceEntry」</b>：
 * {@code plugins/learner} <b>刻意不依赖</b> {@code experience-core}
 * （{@code plugins/learner/build.gradle} 注释：嵌套会与 ac/rdd 一样出 JPMS ResolutionException，
 * 而 {@code plugins/experience} 把 experience-core 内嵌进自己的 fat jar，
 * 两个 mod 各带一份同名类 = 运行时炸）。
 * 所以这里只保证<b>键名与 {@code ExperienceEntry.toJson()} 完全一致</b>，
 * 由 {@link #ENTRY_KEYS} 把这份契约写死；单测
 * {@code ExperienceDraftKeysBindToRealEntryTest} 会<b>直接读 experience-core 的源文件</b>
 * 提取 {@code toJson()} 的真实键做集合相等断言 —— 那边改键名，这边会红。</p>
 *
 * <p><b>刻意不编造的字段</b>（B21 / Codex 审核 P1-1）：
 * <ul>
 *   <li><b>没有 {@code type}</b>。七字段里根本没有「类型」这一项；早先从
 *       {@code actions} 推断（USE_CARRIER ⇒ POLICY）是<b>把操作建议当成分类事实</b> ——
 *       同一条经验仅因附带建议不同就会换分类，进而换掉 {@code stableKey} 与指纹。
 *       现在只给 {@link #suggestedType()} 当<b>建议</b>，并标 {@code needs_classification}，
 *       分类由发布方（AI 调 {@code experience_learn}，那里 type 是必填）明确补齐。</li>
 *   <li><b>没有 {@code tool_names}</b>。映射不出来就不放这个键，不用「无」「未知」冒充。</li>
 *   <li><b>不截断 {@code title}</b>。title 是 id 的一部分，截断会让两条本该不同的长标题
 *       撞成同一条（Codex 审核 P1-4）。展示用的短版另放 {@code title_display}。</li>
 * </ul>
 */
public record ExperienceDraft(
        JsonObject entry,
        String suggestedType,
        String suggestedTypeWhy,
        String titleDisplay,
        String evidenceNote
) {

    /**
     * {@code ExperienceEntry.toJson()} 里的键（不含 {@code id} / {@code v} —— 那是记录层自己写的）。
     *
     * <p>真源是 {@code experience-core/.../api/ExperienceEntry.java} 的 {@code toJson()}。
     * 本列表与它的一致性由 {@code ExperienceDraftKeysBindToRealEntryTest} 读源文件核对。</p>
     */
    public static final List<String> ENTRY_KEYS = List.of(
            "type", "title", "description", "rationale", "root_cause", "recommended_response",
            "trigger_strings", "tool_names", "tags",
            "maturity", "verified_count", "priority", "counterexamples",
            "created_at", "verified_at", "last_accessed_at");

    /** 本映射实际会写的键（其余由记录层落盘时补，或刻意不写）。 */
    public static final List<String> WRITTEN_KEYS = List.of(
            "title", "description", "rationale", "root_cause", "recommended_response",
            "trigger_strings", "tags", "maturity", "priority");

    /** 展示用标题上限（<b>只影响展示，不影响落盘</b>）。 */
    public static final int DISPLAY_TITLE_CHARS = 60;

    /**
     * 从判定 + 备忘录产出草稿。
     *
     * @param v 判定（七字段在 {@code v.experience()}）
     * @param m 对应的备忘录（提供 stage 作为出处；可为 {@code null}）
     */
    public static ExperienceDraft from(Verdict v, Memo m) {
        if (v == null || v.experience() == null) {
            throw new IllegalArgumentException("verdict carries no structured experience");
        }
        Experience x = v.experience();
        JsonObject o = new JsonObject();

        // ★ 不写 type。完整标题也不截断 —— 两者都会影响身份键。
        o.addProperty("title", x.mechanism());
        o.addProperty("title_display", clip(x.mechanism(), DISPLAY_TITLE_CHARS));

        // description 是 learn() 的必填项：现象 + 因果链，缺一就没法检索。
        // 带标签写，别把前置条件拼成「因果链的下一步」—— Codex 审核 P1-3 提到这点。
        String description = joinNonBlank("；",
                x.mechanism(),
                x.derivation().isBlank() ? "" : "应对步骤：" + x.derivation(),
                x.observableSignal().isBlank() ? "" : "判据：" + x.observableSignal());
        o.addProperty("description", description);

        // ★ evidence 在 ExperienceEntry 里没有对应槽位。不丢的办法是并进 rationale，
        //   并把原始值单列（explain().experience_evidence）供监测台核对。
        String rationale = joinNonBlank("；",
                x.efficiency().isBlank() ? "" : "收益：" + x.efficiency(),
                x.evidence().isBlank() ? "" : "证据：" + x.evidence());
        o.addProperty("rationale", rationale);

        o.addProperty("root_cause", x.mechanism());

        // 适用 / 不适用条件必须有标签，且不许被拼成一步因果链的尾巴。
        String recommended = joinNonBlank("；",
                x.preconditions().isBlank() ? "" : "适用条件：" + x.preconditions(),
                x.failureConditions().isBlank() ? "" : "禁用条件：" + x.failureConditions(),
                x.derivation().isBlank() ? "" : "应对步骤：" + x.derivation());
        o.addProperty("recommended_response", recommended);

        // trigger_strings 只承担「检索线索」，不承担「适用条件校验」（Codex 审核 P1-3）。
        // 所以这里放的是短线索，不放整句正文。
        JsonArray triggers = new JsonArray();
        Set<String> tset = new LinkedHashSet<>();
        addIfPresent(tset, x.preconditions());
        addIfPresent(tset, x.failureConditions());
        addIfPresent(tset, x.observableSignal());
        if (m != null) {
            addIfPresent(tset, m.stage());
        }
        for (String t : tset) {
            triggers.add(t);
        }
        o.add("trigger_strings", triggers);

        JsonArray tags = new JsonArray();
        tags.add("learner");
        if (m != null && m.id() != null && !m.id().isBlank()) {
            tags.add("memo:" + m.id());   // 出处可回溯到具体那条备忘录
        }
        o.add("tags", tags);

        // maturity：一次成功未验证 —— doc 59 D5「允许进目录但必须标待验证」。
        // OBSERVED 就是「只被观察过一次」；VERIFIED 要 3 次真实成功（TBD-1）。
        o.addProperty("maturity", "OBSERVED");
        o.addProperty("priority", priorityFrom(v.confidence()));

        // 刻意不写 type / tool_names / counterexamples / *_at（见类注释）
        return new ExperienceDraft(o, suggestType(v), describeSuggestion(v),
                clip(x.mechanism(), DISPLAY_TITLE_CHARS), x.evidence());
    }

    /**
     * <b>建议</b>类型，不是结论 —— 交给发布方定。
     *
     * <p>七字段整套围绕「某条件下会出什么问题 / 怎么判断 / 怎么应对」，
     * 形状上更接近失败经验；同时建议用携带器时更像一条「以后怎么办」的规则。
     * 但这只是形状线索，<b>不足以断言分类</b>，所以只进 {@code mapping.suggested_type}。</p>
     */
    private static String suggestType(Verdict v) {
        if (v.actions() != null && v.actions().contains(Verdict.Action.USE_CARRIER)) {
            return "POLICY";
        }
        return "FAILURE";
    }

    private static String describeSuggestion(Verdict v) {
        List<String> names = new ArrayList<>();
        if (v.actions() != null) {
            for (Verdict.Action a : v.actions()) {
                names.add(a.name());
            }
        }
        return "只是建议，来自 actions=" + names + "（七字段里没有类型这一项，分类请由发布方定）";
    }

    /**
     * 置信度 → priority。
     *
     * <p>⚠️ 这是<b>排序策略</b>，不是事实映射：检索器会把 priority 直接当权重乘。
     * 说清楚它的来源，别让下游以为「80 分」意味着「这条经验 80% 对」。</p>
     */
    static int priorityFrom(double confidence) {
        double c = Math.max(0.0, Math.min(1.0, confidence));
        return (int) Math.round(1 + c * 99);
    }

    private static void addIfPresent(Set<String> into, String s) {
        if (s != null && !s.isBlank()) {
            into.add(s.trim());
        }
    }

    private static String joinNonBlank(String sep, String... parts) {
        List<String> keep = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.isBlank()) {
                keep.add(p.trim());
            }
        }
        return String.join(sep, keep);
    }

    /**
     * 展示用截断（加省略号）。
     *
     * <p><b>只允许用在展示字段上</b>。落到 {@code title} 就是拿身份键开刀 ——
     * 两条前 60 字相同的长经验会撞成一条，谁也分不出来。</p>
     */
    static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    /** 本草稿的 JSON（{@code ExperienceEntry} 形状），直接可被 {@code fromJson} 吃。 */
    public JsonObject toJson() {
        return entry;
    }

    /** 给监测台/人看的映射决策（不含 ExperienceEntry 字段）。 */
    public JsonObject explain() {
        JsonObject o = new JsonObject();
        o.addProperty("needs_classification", true);
        o.addProperty("suggested_type", suggestedType);
        o.addProperty("suggested_type_why", suggestedTypeWhy);
        o.addProperty("title_display_truncated_to", DISPLAY_TITLE_CHARS);
        if (evidenceNote != null && !evidenceNote.isBlank()) {
            o.addProperty("experience_evidence", evidenceNote);
        }
        return o;
    }
}