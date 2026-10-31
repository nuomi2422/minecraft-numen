package com.dwinovo.numen.plugins.learner.blocks;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 从 {@code monitor/context.jsonl} 生成环境块（159 D 的数据源适配器）。
 *
 * <h2>这份文件为什么值钱</h2>
 * 161 §4 实测：`EntityAgentLoop:2759` 把<b>当时真正拼进请求</b>的整段 {@code <runtime_state>}
 * 原样写进 {@code context.jsonl}，注释里写着「never rebuild a past prompt using today's inventory」；
 * `NumenLlmClient:337` 另存了完整 wire body。
 * 也就是说 —— **"执行者当时看到了什么"是有原始记录的**，不需要事后重建。
 * 158 §4 明令禁止「用事后完整日志反推当时看到了什么」，而这份文件恰好是反例：
 * 它不是在事后重建，它就是在当时写下的。
 *
 * <h2>只产出 `DECISION_CONTEXT`，不产出 `RETRIEVAL_TRACE`</h2>
 * 从 wire body 里硬解析「哪些经验被注入了、哪些被预算裁掉了」是不可靠的（要跟 XML 结构绑死，
 * 一改排版就错）。161 找到的真源是 {@code expmem.jsonl} 的 `budget_decision`（kept/dropped/reason）。
 * 与其在这里猜，不如让那一类块暂时缺席 —— 缺一类块是可统计的，猜错一类块是查不出来的。
 *
 * <h2>三条不编的线</h2>
 * <ul>
 *   <li>时间戳解析不了 ⇒ `capturedAt = 0`（渲染成 UNKNOWN），<b>不</b>用读取时刻顶替；
 *       用读取时刻会让一份三天前的上下文看起来是刚采的，而"新鲜度"正是判断它能否用来归因的关键；</li>
 *   <li>坏行/不认识的 type ⇒ 跳过并计数，<b>不</b>抛、也不静默；</li>
 *   <li>别的同伴的记录 ⇒ 跳过，<b>不</b>混进本样本（跨同伴串味是最难复现的一类错）。</li>
 * </ul>
 *
 * <p>纯 JVM、无 IO（吃的是行列表），可离线用固定夹具测试。
 */
public final class ContextBlockSource {

    /** 默认新鲜度阈值：超过就标 STALE。与复盘窗口同量级，未实测校准。 */
    public static final long DEFAULT_STALE_AFTER_MILLIS = 10 * 60 * 1000L;

    private ContextBlockSource() {
    }

    /**
     * 选哪一段。
     *
     * @param companionId 只取这个同伴；空串表示不过滤
     * @param fromMillis  起始时间（含）；{@code <= 0} 表示不限
     * @param toMillis    结束时间（含）；{@code <= 0} 表示不限
     */
    public record Selection(String companionId, long fromMillis, long toMillis) {
        public Selection {
            companionId = companionId == null ? "" : companionId.trim();
        }

        public static Selection all() {
            return new Selection("", 0L, 0L);
        }
    }

    /**
     * 抽取结果。
     *
     * @param skipped  被跳过的行数（坏行 + 别的同伴 + 时间窗外），诚实计数
     * @param problems 至多若干条原因样本，给排障用
     */
    public record Result(List<EnvBlock> blocks, int skipped, List<String> problems) {
        public Result {
            blocks = blocks == null ? List.of() : List.copyOf(blocks);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }
    }

    /**
     * 从 JSONL 行生成块。
     *
     * @param nowMillis        当前时刻（用于算新鲜度，不参与 capturedAt）
     * @param staleAfterMillis 超过多久算 STALE
     */
    public static Result extract(List<String> lines, Selection selection, long nowMillis, long staleAfterMillis) {
        List<EnvBlock> out = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<String> usedIds = new LinkedHashSet<>();
        int skipped = 0;
        if (lines == null || lines.isEmpty()) {
            return new Result(out, 0, problems);
        }
        Selection sel = selection == null ? Selection.all() : selection;

        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            JsonObject env;
            try {
                JsonElement parsed = JsonParser.parseString(line);
                if (!parsed.isJsonObject()) {
                    skipped++;
                    addProblem(problems, "not_object");
                    continue;
                }
                env = parsed.getAsJsonObject();
            } catch (RuntimeException e) {
                // 坏行不能炸整条链路：今天写坏一行，不该让昨天的样本读不出来。
                skipped++;
                addProblem(problems, "bad_json: " + e.getClass().getSimpleName());
                continue;
            }

            String type = str(env, "type");
            JsonObject data = env.has("data") && env.get("data").isJsonObject()
                    ? env.getAsJsonObject("data") : null;
            if (data == null) {
                skipped++;
                addProblem(problems, "no_data_field");
                continue;
            }

            String companion = "runtime_state".equals(type)
                    ? str(data, "companion_id")
                    : str(data, "companionId");
            if (!sel.companionId().isBlank() && !sel.companionId().equals(companion)) {
                skipped++;
                continue;
            }

            // 解析失败统一归一到 0（唯一哨兵值）：留 -1 会让「未知」在比较里表现成"很老"，
            // 于是 UNKNOWN 被悄悄读成 STALE。所有 <=0 都按 UNKNOWN 渲染。
            long capturedAt = Math.max(0L, parseInstant(str(env, "timestamp")));
            if (capturedAt > 0) {
                if (sel.fromMillis() > 0 && capturedAt < sel.fromMillis()) {
                    skipped++;
                    continue;
                }
                if (sel.toMillis() > 0 && capturedAt > sel.toMillis()) {
                    skipped++;
                    continue;
                }
            }

            EnvBlock block = "runtime_state".equals(type)
                    ? runtimeStateBlock(env, data, capturedAt, nowMillis, staleAfterMillis)
                    : "llm_request".equals(type)
                    ? llmRequestBlock(env, data, capturedAt, nowMillis, staleAfterMillis)
                    : null;
            if (block == null) {
                skipped++;
                addProblem(problems, "unknown_type: " + type);
                continue;
            }
            out.add(new EnvBlock(block.kind(), uniqueId(block.blockId(), usedIds), block.capturedAt(),
                    block.source(), block.coverage(), block.freshness(), block.confidence(),
                    block.summary(), block.body(), block.truncated()));
        }
        return new Result(out, skipped, problems);
    }

    private static EnvBlock runtimeStateBlock(JsonObject env, JsonObject data, long capturedAt,
                                              long nowMillis, long staleAfter) {
        String xml = str(data, "context");
        String turn = str(data, "turn");
        String origin = str(data, "origin");
        String attempt = str(data, "attempt");
        String id = turn.isBlank() ? "dc-" + str(env, "event_id") : "dc-turn-" + turn;
        return new EnvBlock(
                BlockKind.DECISION_CONTEXT,
                id,
                capturedAt,
                "context.jsonl#runtime_state",
                // 内容是「当时那一次请求原样拼出来的」，对这个范围是完整的；
                // 但它只覆盖那一轮 —— 所以摘要里写明 turn，不许被当成整段时间的观察。
                EnvBlock.Coverage.COMPLETE,
                freshnessOf(capturedAt, nowMillis, staleAfter),
                // 逐字落盘的原始内容，可信度最高档位。
                EnvBlock.Confidence.HIGH,
                "turn=" + (turn.isBlank() ? "UNKNOWN" : turn)
                        + " origin=" + (origin.isBlank() ? "UNKNOWN" : origin)
                        + " attempt=" + (attempt.isBlank() ? "UNKNOWN" : attempt)
                        + " chars=" + xml.length(),
                xml,
                false);
    }

    private static EnvBlock llmRequestBlock(JsonObject env, JsonObject data, long capturedAt,
                                            long nowMillis, long staleAfter) {
        String requestId = str(data, "requestId");
        JsonElement request = data.get("request");
        String body = request == null || request.isJsonNull()
                ? ""
                : (request.isJsonPrimitive() ? request.getAsString() : request.toString());
        return new EnvBlock(
                BlockKind.DECISION_CONTEXT,
                requestId.isBlank() ? "req-" + str(env, "event_id") : "req-" + requestId,
                capturedAt,
                "context.jsonl#llm_request",
                EnvBlock.Coverage.COMPLETE,
                freshnessOf(capturedAt, nowMillis, staleAfter),
                // wire body 含传输层包装，比 runtime_state 的逐字段 XML 低一档。
                EnvBlock.Confidence.MEDIUM,
                "phase=" + (str(data, "phase").isBlank() ? "UNKNOWN" : str(data, "phase"))
                        + " model=" + (str(data, "model").isBlank() ? "UNKNOWN" : str(data, "model"))
                        + " chars=" + body.length(),
                body,
                false);
    }

    private static EnvBlock.Freshness freshnessOf(long capturedAt, long nowMillis, long staleAfter) {
        if (capturedAt <= 0) {
            // 没时间戳就没有新鲜度可言：写 UNKNOWN，不写 STALE 也不写 CURRENT。
            return EnvBlock.Freshness.UNKNOWN;
        }
        long age = nowMillis - capturedAt;
        return age <= staleAfter ? EnvBlock.Freshness.CURRENT : EnvBlock.Freshness.STALE;
    }

    /** 同一 turn 出现两次（重试）时保持唯一：{@code id#2}。BlockReader 拒绝重复 id。 */
    private static String uniqueId(String id, Set<String> used) {
        if (used.add(id)) {
            return id;
        }
        int n = 2;
        while (!used.add(id + "#" + n)) {
            n++;
        }
        return id + "#" + n;
    }

    private static void addProblem(List<String> problems, String why) {
        if (problems.size() < 10) {
            problems.add(why);
        }
    }

    /** ISO-8601 解析；失败返回 -1（调用方据此写 UNKNOWN），<b>不用当前时刻顶替</b>。 */
    public static long parseInstant(String raw) {
        if (raw == null || raw.isBlank()) {
            return -1L;
        }
        try {
            return Instant.parse(raw.trim()).toEpochMilli();
        } catch (DateTimeParseException e) {
            return -1L;
        }
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? "" : e.getAsString();
    }
}
