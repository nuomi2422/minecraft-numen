package com.dwinovo.numen.plugins.learner.funnel;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从现有日志回放漏斗七阶（H2，方案见 164 号文档）。
 *
 * <h2>为什么是"回放"而不是"新增写者"</h2>
 * 164 的结论：`context.jsonl` 的 `retrieval_snapshot.refs[]` 已经逐条目带
 * `ref / rendered / reason`，`expmem.jsonl` 有 `expanded / trial_used / usage_recorded`。
 * 现有日志<b>够用</b> —— 缺的是读它的人。所以在 learner 侧做一个纯解析器，
 * 不改 experience 插件、不新增写者、不引入跨插件编译依赖（160 §7）。
 *
 * <h2>三条不猜的线</h2>
 * <ul>
 *   <li>不认识的原因码 → 记进 {@code unknownReasons} 并计数，<b>不</b>默认当"适用"；
 *       原因码是字符串契约，experience 侧改词时 learner 不许静默把"过滤"读成"通过"；</li>
 *   <li>没有 ref 的事件 → 跳过并计数，不编一个 ref；</li>
 *   <li>`CANCELED` 是 EFFECT 的一种，<b>但不是成功</b> —— 效果档位留在 reason 里，由读者判断。</li>
 * </ul>
 *
 * <p>纯 JVM、无 IO（吃行列表），可离线用固定夹具测试。
 */
public final class FunnelReplay {

    /**
     * 已知的过滤原因码。
     *
     * <p>⚠ 这两个文件<b>各自</b>产出 reason，所以词表必须两处都收：
     * <ul>
     *   <li>`plugins/experience/.../ExperienceStoreRetrievalBridge`：`maturity_below_verified` /
     *       `retracted` / `warning_kind` / `role_not_granted` / `applicability_fail` / `pinned_duplicate`；</li>
     *   <li>`ai/.../ExperienceRetrievalProvider`：`not_retrievable` / `draft_or_suspended`。</li>
     * </ul>
     * 后两个是 2026-10-31 用真实日志回放时由 `unknownReasons` 报出来的（164 §8），
     * 不是想起来的 —— 这就是"未知码不许静默当适用"的价值。
     */
    public static final Set<String> KNOWN_REASONS = Set.of(
            "maturity_below_verified", "retracted", "warning_kind",
            "role_not_granted", "applicability_fail", "pinned_duplicate",
            "not_retrievable", "draft_or_suspended",
            // `ok` 是"没被过滤"的显式标记（不是失败码）。真实日志里 rendered=false 的候选也带它，
            // 所以必须认识，否则每次回放都会把它当成"未知码漂移"报警。
            "ok");

    /** 终止态（有这些状态说明这一轮已经有结果了）。 */
    public static final Set<String> TERMINAL_STATES = Set.of(
            "SUCCESS", "FAIL", "CANCELED", "UNKNOWN");

    private FunnelReplay() {
    }

    /**
     * 回放结果。
     *
     * @param skipped        真正的"读不进来"（坏 JSON / 没有 ref）
     * @param outOfWindow    时间窗外的行数 —— <b>不能</b>和 skipped 混在一起：
     *                       一个是"日志坏了"，一个是"这不是我要的那段时间"，处理方式完全不同
     * @param unknownReasons 遇到过的不认识的原因码（去重）
     */
    public record Result(List<FunnelLedger.Entry> entries, int skipped, int outOfWindow,
                         List<String> unknownReasons, List<String> problems) {
        public Result {
            entries = entries == null ? List.of() : List.copyOf(entries);
            unknownReasons = unknownReasons == null ? List.of() : List.copyOf(unknownReasons);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        /** 不分窗口的旧视图（老调用点/老用例用）。 */
        public Result(List<FunnelLedger.Entry> entries, int skipped,
                      List<String> unknownReasons, List<String> problems) {
            this(entries, skipped, 0, unknownReasons, problems);
        }
    }

    /**
     * 回放两本日志。
     *
     * @param nowMillis 用于给记录打时间（原始事件若带 timestamp 会用原始值）
     */
    public static Result replay(List<String> contextLines, List<String> expmemLines, long nowMillis) {
        return replay(contextLines, expmemLines, 0L, 0L, nowMillis);
    }

    /**
     * 按<strong>时间窗</strong>回放（164 §8 的结论：按字节取尾会让两本日志落在不同时段）。
     *
     * <p>为什么必须按时间对齐：两本日志每行大小差两个数量级（context 每行含整段 XML），
     * 用"同样字节数取尾"取到的是两段完全不同的时间，于是会出现
     * `ADOPTED > RECALLED` 这种荒谬计数。
     *
     * @param fromMillis 起始（含）；{@code <= 0} 不限
     * @param toMillis   结束（含）；{@code <= 0} 不限
     */
    public static Result replay(List<String> contextLines, List<String> expmemLines,
                                long fromMillis, long toMillis, long nowMillis) {
        List<FunnelLedger.Entry> out = new ArrayList<>();
        Set<String> unknownReasons = new LinkedHashSet<>();
        List<String> problems = new ArrayList<>();
        // [0]=读不进来  [1]=时间窗外（两者必须分开：一个是日志坏了，一个是不在这段时间）
        int[] skipped = {0, 0};

        for (String line : safe(contextLines)) {
            JsonObject env = parse(line, skipped);
            if (env == null) {
                continue;
            }
            if (!"retrieval_snapshot".equals(str(env, "type"))) {
                continue;
            }
            JsonObject data = obj(env, "data");
            if (data == null) {
                skipped[0]++;
                continue;
            }
            String occurrence = firstNonBlank(str(env, "event_id"), str(data, "task_fingerprint"));
            long stamp = stampOf(env);
            long at = stamp > 0 ? stamp : nowMillis;
            if (stamp > 0 && !inWindow(stamp, fromMillis, toMillis)) {
                // 时间窗外的行单独计数：它既不是"日志坏了"，也不是"没有发生"。
                skipped[1]++;
                continue;
            }
            if (stamp <= 0) {
                addProblem(problems, "no_timestamp");
            }
            FunnelLedger.Caller caller = "LEARNER".equalsIgnoreCase(str(data, "role"))
                    ? FunnelLedger.Caller.LEARNER : FunnelLedger.Caller.EXECUTOR;

            JsonElement refsEl = data.get("refs");
            if (refsEl == null || !refsEl.isJsonArray()) {
                continue;
            }
            for (JsonElement e : refsEl.getAsJsonArray()) {
                if (!e.isJsonObject()) {
                    continue;
                }
                JsonObject ref = e.getAsJsonObject();
                String entryRef = str(ref, "ref");
                if (entryRef.isBlank()) {
                    skipped[0]++;
                    addProblem(problems, "ref_without_id");
                    continue;
                }
                String reason = str(ref, "reason");
                boolean rendered = Boolean.parseBoolean(str(ref, "rendered"));
                boolean applicable = !"applicability_fail".equals(reason);

                if (!reason.isBlank() && !KNOWN_REASONS.contains(reason)) {
                    // 不认识的原因码：照记，但必须让漂移可见。
                    unknownReasons.add(reason);
                }

                // 第 0 阶必须能记否定结论，否则"未适用"永远查不到。
                out.add(new FunnelLedger.Entry(entryRef, FunnelStage.APPLICABLE, caller, at,
                        applicable ? "applicable" : reason, occurrence));
                out.add(new FunnelLedger.Entry(entryRef, FunnelStage.RECALLED, caller, at,
                        reason, occurrence));
                if (rendered) {
                    out.add(new FunnelLedger.Entry(entryRef, FunnelStage.PRESENTED, caller, at,
                            "", occurrence));
                }
            }
        }

        for (String line : safe(expmemLines)) {
            JsonObject env = parse(line, skipped);
            if (env == null) {
                continue;
            }
            String type = str(env, "type");
            JsonObject data = obj(env, "data");
            if (data == null) {
                continue;
            }
            String ref = firstNonBlank(str(data, "ref"), str(data, "entryRef"));
            if (ref.isBlank()) {
                continue;
            }
            long stamp = stampOf(env);
            long at = stamp > 0 ? stamp : nowMillis;
            if (stamp > 0 && !inWindow(stamp, fromMillis, toMillis)) {
                // 时间窗外的行单独计数：它既不是"日志坏了"，也不是"没有发生"。
                skipped[1]++;
                continue;
            }
            if (stamp <= 0) {
                addProblem(problems, "no_timestamp");
            }
            String occurrence = firstNonBlank(str(env, "event_id"), ref);
            switch (type) {
                case "expanded" -> out.add(new FunnelLedger.Entry(ref, FunnelStage.EXPANDED,
                        FunnelLedger.Caller.EXECUTOR, at, str(data, "version"), occurrence));
                case "trial_used" -> out.add(new FunnelLedger.Entry(ref, FunnelStage.ADOPTED,
                        FunnelLedger.Caller.EXECUTOR, at,
                        // 「采用」没有独立事件，只能拿试用当近似 —— 标注出来，别让读者以为是精确值。
                        "approx_adopted:" + str(data, "outcome"), occurrence));
                case "usage_recorded" -> {
                    String state = str(data, "state").toUpperCase(java.util.Locale.ROOT);
                    out.add(new FunnelLedger.Entry(ref, FunnelStage.EXECUTED,
                            FunnelLedger.Caller.EXECUTOR, at, state, occurrence));
                    if (TERMINAL_STATES.contains(state)) {
                        // CANCELED / UNKNOWN 也是"有结果"，但不是成功：档位留在 reason 里。
                        out.add(new FunnelLedger.Entry(ref, FunnelStage.EFFECT,
                                FunnelLedger.Caller.EXECUTOR, at, state, occurrence));
                    }
                }
                default -> {
                    // 其它事件（learned/verified/recalled...）与漏斗无关，静默跳过。
                }
            }
        }
        return new Result(out, skipped[0], skipped[1], List.copyOf(unknownReasons), problems);
    }

    private static List<String> safe(List<String> lines) {
        return lines == null ? List.of() : lines;
    }

    private static JsonObject parse(String line, int[] skipped) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonElement e = JsonParser.parseString(line);
            if (!e.isJsonObject()) {
                skipped[0]++;
                return null;
            }
            return e.getAsJsonObject();
        } catch (RuntimeException ex) {
            // 坏行不许炸整条回放：今天写坏一行，不该让昨天的漏斗读不出来。
            skipped[0]++;
            return null;
        }
    }

    private static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static String str(JsonObject o, String key) {
        if (o == null) {
            return "";
        }
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }

    /**
     * 原始时间戳；缺失或解析不了返回 {@code -1}。
     *
     * <p>为什么不直接回退到 nowMillis：回退会让一条没有时间戳的记录<b>看起来是刚发生的</b>，
     * 于是它永远落在时间窗内 —— 而它可能是一周前的。窗口判定必须能看见"没有时间"这件事。
     */
    private static long stampOf(JsonObject env) {
        String ts = str(env, "timestamp");
        if (ts.isBlank()) {
            return -1L;
        }
        try {
            return java.time.Instant.parse(ts).toEpochMilli();
        } catch (RuntimeException e) {
            return -1L;
        }
    }

    /** 时间窗判定：{@code <=0} 视为该端不限。 */
    private static boolean inWindow(long stamp, long fromMillis, long toMillis) {
        if (fromMillis > 0 && stamp < fromMillis) {
            return false;
        }
        return toMillis <= 0 || stamp <= toMillis;
    }

    private static String firstNonBlank(String... xs) {
        for (String x : xs) {
            if (x != null && !x.isBlank()) {
                return x;
            }
        }
        return "";
    }

    private static void addProblem(List<String> problems, String why) {
        if (problems.size() < 10) {
            problems.add(why);
        }
    }
}
