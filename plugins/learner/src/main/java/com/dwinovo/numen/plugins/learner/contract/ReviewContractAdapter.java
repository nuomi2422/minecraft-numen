package com.dwinovo.numen.plugins.learner.contract;

import com.dwinovo.numen.plugins.learner.core.Experience;
import com.dwinovo.numen.plugins.learner.core.Verdict;

import java.util.ArrayList;
import java.util.List;

/**
 * 旧 {@link Verdict} → 新契约 {@link Submission.Draft} 的<strong>适配器</strong>（159 E）。
 *
 * <h2>它为什么必须存在，而且必须承认自己是"有损"的</h2>
 * 现有复盘产物是七字段经验草稿 + 四类草稿位，<b>没有结构化证据引用</b>：
 * {@code Experience.evidence} 是一段自由文本，不是 `block:`/`event:` 引用。
 * 所以这里做不出一条"满足新契约"的提交 —— 这是事实，不是实现缺陷。
 *
 * <p>适配器的职责因此只有两个：
 * <ol>
 *   <li>把旧载荷<b>如实搬</b>到新结构（不补字段、不猜证据）；</li>
 *   <li>让闸门能在真实复盘路径上跑一遍，<b>量出</b>「按新契约会有多少条不通过」。</li>
 * </ol>
 *
 * <h2>为什么映射时宁缺不补</h2>
 * 把 {@code observableSignal} 硬塞成 triggerConditions、把自由文本证据硬塞成 {@code event:} 引用，
 * 会让影子统计显示"大部分通过" —— 而那个数字是假的，后面据此把闸门切到 ENFORCE 就会立刻炸。
 * 现在如实映射，影子数据才会告诉真相：**旧格式基本过不了新契约**。
 *
 * <p>纯 JVM，可离线单测。
 */
public final class ReviewContractAdapter {

    private ReviewContractAdapter() {
    }

    /**
     * 把一条判定搬成一次提交。
     *
     * @param sampleId 这条判定对应的样本 id；旧流程没有，传 memoId 或空
     */
    public static Submission.Draft toSubmission(Verdict v, String sampleId, Submission.Mode mode) {
        return toSubmission(v, sampleId, mode, null);
    }

    /**
     * 带证据引用的版本（2026-11-02，F9 之后）：{@code windowFirst} 是 memo 快照里的原始事件 id，
     * 映射成 {@code event:<id>}（{@link EvidenceRef} 既有能力）。
     *
     * <p>前提：调用方必须先用 {@link #evidenceAllowed(String)} 判断源写者 —— 只有
     * {@code journaled_by} 明确且不是 client 注入的 memo 才允许带证据；否则留空，
     * 让质量门继续判 {@code claim_without_evidence}（宁可不放行，也不给假失败开门）。
     */
    public static Submission.Draft toSubmission(Verdict v, String sampleId, Submission.Mode mode,
                                                String windowFirst) {
        if (v == null) {
            return new Submission.Draft(mode, sampleId, new ReviewDraft.Draft(sampleId, List.of()), List.of());
        }
        List<ReviewDraft.Claim> claims = new ArrayList<>();
        List<EvidenceRef> evidence = List.of();
        if (windowFirst != null && !windowFirst.isBlank()) {
            try {
                evidence = List.of(EvidenceRef.parse("event:" + windowFirst.trim()));
            } catch (RuntimeException ignored) {
                evidence = List.of();   // 形不成合法引用就不放，不伪造
            }
        }
        claims.add(new ReviewDraft.Claim(ReviewDraft.Section.FACT,
                clip(v.reasoning(), 400), evidence));

        List<ArtifactDraft> artifacts = new ArrayList<>();
        for (Verdict.Action a : v.actions()) {
            ArtifactDraft d = switch (a) {
                case WRITE_EXPERIENCE -> fromExperience(v.experience(), v.memoId(), evidence);
                case USE_AC -> v.acScriptDraft() == null || v.acScriptDraft().isBlank()
                        ? null
                        : new ArtifactDraft(ArtifactKind.ACX_REQUEST,
                        "AC from " + nz(v.memoId()), clip(v.acScriptDraft(), 80), v.acScriptDraft(),
                        // 2026-11-02：AC 草稿里的 triggerConditions / evidence 直接抬进产物声明。
                        // 只搬草稿自己写了的（不补造），缺失仍会被契约如实判 missing_field。
                        listField(v.acScriptDraft(), "triggerConditions"), List.of(), List.of(), "",
                        evidenceField(v.acScriptDraft()), List.of(nz(v.memoId())), "EXECUTOR");
                case USE_CARRIER -> v.carrierDraft() == null || v.carrierDraft().isBlank()
                        ? null
                        : new ArtifactDraft(ArtifactKind.AUTO_ACTION,
                        "carrier from " + nz(v.memoId()), clip(v.carrierDraft(), 80), v.carrierDraft(),
                        // 2026-11-02：三类产物同样从各自 draft JSON 抬 triggerConditions/evidence（只搬不造）。
                        listField(v.carrierDraft(), "triggerConditions"), List.of(), List.of(), "",
                        evidenceField(v.carrierDraft()), List.of(nz(v.memoId())), "EXECUTOR");
                case SELF_COMPILE -> v.selfCompileRequest() == null || v.selfCompileRequest().isBlank()
                        ? null
                        : new ArtifactDraft(ArtifactKind.CAPABILITY_REQUEST,
                        "capability from " + nz(v.memoId()), clip(v.selfCompileRequest(), 80),
                        v.selfCompileRequest(),
                        listField(v.selfCompileRequest(), "triggerConditions"), List.of(), List.of(), "",
                        evidenceField(v.selfCompileRequest()), List.of(nz(v.memoId())), "EXECUTOR");
                case SIDE_TASK -> v.sideTaskDraft() == null || v.sideTaskDraft().isBlank()
                        ? null
                        : new ArtifactDraft(ArtifactKind.SIDE_TASK,
                        "side task from " + nz(v.memoId()), clip(v.sideTaskDraft(), 80), v.sideTaskDraft(),
                        listField(v.sideTaskDraft(), "triggerConditions"), List.of(), List.of(), "",
                        evidenceField(v.sideTaskDraft()), List.of(nz(v.memoId())), "EXECUTOR");
                case NO_ACTION, NEEDS_HUMAN -> null;
            };
            if (d != null) {
                artifacts.add(d);
            }
        }
        return new Submission.Draft(mode, sampleId, new ReviewDraft.Draft(sampleId, claims), artifacts);
    }

    /** 从草稿 JSON 里读字符串数组字段；缺键/坏 JSON 返回空表（不猜）。 */
    static List<String> listField(String json, String key) {
        if (json == null || json.isBlank()) return List.of();
        try {
            com.google.gson.JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (!o.has(key)) return List.of();
            com.google.gson.JsonElement el = o.get(key);
            List<String> out = new java.util.ArrayList<>();
            // 2026-11-06：模型常把 evidence 写成逗号字符串；字符串按单段列表容忍，不整条丢弃。
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                for (String part : el.getAsString().split(",")) {
                    if (!part.isBlank()) out.add(part.trim());
                }
                return out;
            }
            if (!el.isJsonArray()) return List.of();
            for (com.google.gson.JsonElement e : el.getAsJsonArray()) {
                if (e.isJsonPrimitive() && !e.getAsString().isBlank()) out.add(e.getAsString().trim());
            }
            return out;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /** 从草稿 JSON 的 evidence 数组里读合法引用（event:… / block:…）；非法项丢弃，不伪造。 */
    static List<EvidenceRef> evidenceField(String json) {
        List<EvidenceRef> out = new java.util.ArrayList<>();
        for (String raw : listField(json, "evidence")) {
            try {
                EvidenceRef r = EvidenceRef.parse(raw);
                // OTHER = 解析得出但不可核验（没有合法前缀）；不进证据，避免「看起来有证据」。
                if (r.kind() != EvidenceRef.Kind.OTHER) out.add(r);
            } catch (RuntimeException ignored) {
                // 空/坏引用丢弃，不伪造
            }
        }
        return out;
    }

    private static ArtifactDraft fromExperience(Experience e, String memoId, List<EvidenceRef> sourceEvidence) {
        if (e == null) {
            return null;
        }
        String purpose = firstNonBlank(e.mechanism(), e.observableSignal(), "未命名经验");
        // 2026-11-02（审核窗口 07:06）：EXPERIENCE 产物也必须带 triggerConditions/evidence，
        // 否则 WRITE_EXPERIENCE 这条 artifact 永远 missing_field → 契约 REJECT → 不可注入。
        // triggerConditions 用 experience.preconditions（同义：这条机制在什么条件下成立），
        // evidence 用源 memo 的 window_first（经验正是从那个事件得出的），都不补造。
        List<String> trig = e.preconditions() == null || e.preconditions().isBlank()
                ? List.of() : List.of(e.preconditions().trim());
        return new ArtifactDraft(
                ArtifactKind.EXPERIENCE,
                clip(purpose, 40),
                purpose,
                join(e.derivation(), e.efficiency()),
                trig,
                splitOne(e.failureConditions()),
                splitOne(e.preconditions()),
                "",
                sourceEvidence,
                List.of(nz(memoId)),
                String.join(",", e.audience() == null ? List.of() : e.audience()));
    }

    /** 从 memo 快照里取 {@code window_first=<event-id>}；没有/格式不对返回空串（不猜）。 */
    public static String windowFirstOf(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("window_first=([^,\\s]+)").matcher(snapshot);
        return m.find() ? m.group(1).trim() : "";
    }

    /**
     * F9 源写者闸（2026-11-02）：只有写明 {@code journaled_by} 且不是 client 注入的 memo
     * 才允许带 {@code event:} 证据。
     *
     * <p>没有写者标记的老 memo 一律不放行 —— 「不知道来源」不等于「来源可信」。
     * client 注入行（缺显式布尔、由客户端默认 false 凑出来的）是本次要挡的对象。
     *
     * <p><b>边界（如实写）</b>：event:&lt;id&gt; 只保证 id 形态与来源写者，ReviewDraft 不校验该事件
     * 在 tools.jsonl 里是否存在 —— 真正的 resolver 还没接（跨模块索引，见 168 §7.12）。
     * 写者取 memo 窗口的第一条计数行；跨写者窗口只代表那一条。
     */
    public static boolean evidenceAllowed(String snapshot) {
        return evidenceAllowed(snapshot, null);
    }

    /**
     * 带 stage 的版本（2026-11-02，F9 正向路径）：instrumentation 事件（death/loop_detected/
     * asset_mismatch…）由服务端 instrumentation 日志产生，不经过 client 写者那条会缺标签的
     * tool_result 路径 —— 它们的 stage 不是 tool_failure，可以带 event 证据。
     * tool_failure 仍按写者标记判定（缺标签=不可信）。
     */
    public static boolean evidenceAllowed(String snapshot, String stage) {
        if (stage != null && !stage.isBlank() && !"tool_failure".equals(stage)) {
            return true;
        }
        return evidenceAllowedByWriter(snapshot);
    }

    private static boolean evidenceAllowedByWriter(String snapshot) {
        if (snapshot == null || snapshot.isBlank()) return false;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("journaled_by=([^,\\s]+)").matcher(snapshot);
        if (!m.find()) return false;
        String w = m.group(1).trim();
        return !w.isBlank() && !"client".equals(w) && !"unknown".equals(w);
    }

    private static List<String> splitOne(String s) {
        return s == null || s.isBlank() ? List.of() : List.of(s.trim());
    }

    private static String join(String a, String b) {
        String x = a == null ? "" : a;
        String y = b == null ? "" : b;
        if (x.isBlank()) {
            return y;
        }
        return y.isBlank() ? x : x + "\n" + y;
    }

    private static String firstNonBlank(String... xs) {
        for (String x : xs) {
            if (x != null && !x.isBlank()) {
                return x.trim();
            }
        }
        return "";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String clip(String s, int max) {
        String t = s == null ? "" : s.trim().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max);
    }
}
