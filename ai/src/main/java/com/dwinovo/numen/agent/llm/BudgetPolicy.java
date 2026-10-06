package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.ai.AiLog;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * 经济调控（E1 第一步）：按角色（执行/规划/学习）的滑动窗口预算策略。
 *
 * <p><b>本版本只 shadow：只发 {@code budget_advice} 建议事件，绝不拦截任何调用。</b>
 * 配置里写 {@code mode:"enforce"} 也会按 shadow 执行并在日志/事件里如实标注——
 * 真正的拦截（DEFERRED/BLOCKED_BUDGET 等明确原因）是 E1 第二步，需要先跑出基线。
 *
 * <p>配置：{@code <configDir>/budget-policy.json}（不存在 = 默认策略：shadow、全部不限），
 * 首次 install 时另写一份 {@code budget-policy.example.json} 供参考，不覆盖已有示例。
 * 加载入口 {@link #install(Path)}：客户端在 EntityAgentLoop 类加载时装一次；
 * RDD / 学习者插件各自 setup 里装一次（同一文件、幂等，mtime 未变不重读）。
 *
 * <p>计量：{@link #record} 由 {@link NumenLlmClient} 在每次调用完成时记一笔
 * （token 用 totalTokens；usage 缺失按 0 token 记但调用次数与耗时照记）。
 * 窗口 = 最近 {@code windowMinutes} 分钟的滑动窗口，仅存内存、重启清零——
 * 建议用途足够；跨重启对账走 monitor 的 {@code llm_usage} 事件。
 *
 * <p>角色映射（相位 → 角色）见 {@link #roleOf}。本类任何异常都不上抛给请求路径。
 */
public final class BudgetPolicy {

    private BudgetPolicy() {}

    // ---- 角色映射 ----

    /**
     * 相位 → 三脑角色。execution/execution_retry/goal_judging/compaction → execution；
     * stage_a/stage_b/fallback → planning；review → learning；其余 → unknown（只计量不建策）。
     */
    public static String roleOf(String phase) {
        if (phase == null || phase.isBlank()) return "unknown";
        return switch (phase) {
            case "execution", "execution_retry", "goal_judging", "compaction" -> "execution";
            case "stage_a", "stage_b", "fallback" -> "planning";
            case "review" -> "learning";
            default -> "unknown";
        };
    }

    // ---- 配置模型 ----

    /** 单角色限额；0 = 不限。 */
    public record Limit(long tokensPerWindow, long callsPerWindow, long minutesPerWindow) {
        public static final Limit NONE = new Limit(0, 0, 0);

        public boolean isNone() {
            return tokensPerWindow <= 0 && callsPerWindow <= 0 && minutesPerWindow <= 0;
        }
    }

    /** 生效策略快照。{@code mode} 当前只会是 {@code shadow}（enforce 尚未实现，见类注释）。 */
    public record Policy(int version, String mode, int windowMinutes, int adviceCooldownMinutes,
                         Map<String, Limit> roles) {}

    private static final Policy DEFAULT_POLICY = new Policy(0, "shadow", 60, 5, Map.of());

    private static volatile Policy policy = DEFAULT_POLICY;
    private static volatile Path loadedFrom;
    private static volatile long loadedMtimeMs = -1L;

    // ---- 滑动窗口计量 ----

    private record Call(long atMs, long tokens, long millis) {}

    private static final Map<String, ArrayDeque<Call>> METER = new ConcurrentHashMap<>();
    private static final Map<String, Long> LAST_ADVICE_MS = new ConcurrentHashMap<>();
    private static volatile LongSupplier clock = System::currentTimeMillis;

    /** 窗口用量快照。 */
    public record WindowUsage(long calls, long tokens, long millis) {
        public static final WindowUsage EMPTY = new WindowUsage(0, 0, 0);
    }

    /** 一条预算建议（只建议不拦截）。 */
    public record Advice(String role, List<String> exceeded, long usedTokens, long projectedTokens,
                         WindowUsage used, Limit limit, String mode) {}

    /** 记一笔已完成的调用（成功与失败都记；usage 缺失时 tokens 传 0）。 */
    public static void record(String phase, long tokens, long millis) {
        try {
            String role = roleOf(phase);
            ArrayDeque<Call> q = METER.computeIfAbsent(role, r -> new ArrayDeque<>());
            long now = clock.getAsLong();
            synchronized (q) {
                q.addLast(new Call(now, Math.max(0, tokens), Math.max(0, millis)));
                prune(q, now);
            }
        } catch (RuntimeException ex) {
            AiLog.LOG.warn("[numen-econ] 预算计量失败(不影响请求): {}", ex.toString());
        }
    }

    private static void prune(ArrayDeque<Call> q, long now) {
        long cutoff = now - policy.windowMinutes() * 60_000L;
        while (!q.isEmpty() && q.peekFirst().atMs() < cutoff) q.removeFirst();
    }

    /** 该角色当前窗口用量。 */
    public static WindowUsage windowUsage(String role) {
        ArrayDeque<Call> q = METER.get(role);
        if (q == null) return WindowUsage.EMPTY;
        long now = clock.getAsLong();
        synchronized (q) {
            prune(q, now);
            long calls = 0, tokens = 0, millis = 0;
            for (Call c : q) {
                calls++;
                tokens += c.tokens();
                millis += c.millis();
            }
            return new WindowUsage(calls, tokens, millis);
        }
    }

    /**
     * 发出建议前的求值：超限返回一条 {@link Advice}（调用方发 {@code budget_advice} 事件），
     * 否则 null。同一角色在 {@code adviceCooldownMinutes} 内最多建议一次（平稳心跳限频）。
     */
    public static Advice advice(String phase, long estimatedPromptTokens) {
        try {
            Policy p = policy;
            String role = roleOf(phase);
            Limit limit = p.roles().get(role);
            if (limit == null || limit.isNone()) return null;
            WindowUsage used = windowUsage(role);
            long projected = used.tokens() + Math.max(0, estimatedPromptTokens);
            List<String> exceeded = new ArrayList<>();
            if (limit.tokensPerWindow() > 0 && projected > limit.tokensPerWindow()) exceeded.add("tokens");
            if (limit.callsPerWindow() > 0 && used.calls() + 1 > limit.callsPerWindow()) exceeded.add("calls");
            if (limit.minutesPerWindow() > 0 && used.millis() > limit.minutesPerWindow() * 60_000L) {
                exceeded.add("minutes");
            }
            if (exceeded.isEmpty()) return null;
            long now = clock.getAsLong();
            Long last = LAST_ADVICE_MS.get(role);
            if (last != null && now - last < p.adviceCooldownMinutes() * 60_000L) return null;
            LAST_ADVICE_MS.put(role, now);
            return new Advice(role, List.copyOf(exceeded), used.tokens(), projected, used, limit, p.mode());
        } catch (RuntimeException ex) {
            AiLog.LOG.warn("[numen-econ] 预算求值失败(不影响请求): {}", ex.toString());
            return null;
        }
    }

    // ---- 加载 ----

    /**
     * 加载 {@code <configDir>/budget-policy.json}；文件缺失 = 默认策略（shadow、不限）。
     * 同一文件 mtime 未变则不重复读。任何异常都不上抛（观测/建议层）。
     */
    public static void install(Path configDir) {
        if (configDir == null) return;
        try {
            Path file = configDir.resolve("budget-policy.json");
            writeExampleIfMissing(configDir);
            if (!Files.exists(file)) {
                if (!file.equals(loadedFrom) || loadedMtimeMs != -1L) {
                    policy = DEFAULT_POLICY;
                    loadedFrom = file;
                    loadedMtimeMs = -1L;
                    AiLog.LOG.info("[numen-econ] 未发现 budget-policy.json → 默认策略（shadow、全部不限）");
                }
                return;
            }
            long mtime = Files.getLastModifiedTime(file).toMillis();
            if (file.equals(loadedFrom) && mtime == loadedMtimeMs) return;
            Policy parsed = parse(Files.readString(file, StandardCharsets.UTF_8));
            policy = parsed;
            loadedFrom = file;
            loadedMtimeMs = mtime;
            AiLog.LOG.info("[numen-econ] 预算策略已加载: version={}, mode={}, window={}min, 角色限额={}",
                    parsed.version(), parsed.mode(), parsed.windowMinutes(), describeLimits(parsed));
        } catch (Exception ex) {
            policy = DEFAULT_POLICY;
            AiLog.LOG.warn("[numen-econ] 预算策略加载失败，退回默认（shadow、全部不限）: {}", ex.toString());
        }
    }

    private static Policy parse(String text) {
        JsonObject root = JsonParser.parseString(text).getAsJsonObject();
        int version = (int) longOr(root, "version", 1);
        String requestedMode = strOr(root, "mode", "shadow").trim().toLowerCase();
        String mode = "shadow";
        if ("enforce".equals(requestedMode)) {
            AiLog.LOG.warn("[numen-econ] budget-policy.json 请求 mode=enforce，但本版本只实现 shadow"
                    + "（只建议不拦截）→ 按 shadow 执行");
        } else if (!"shadow".equals(requestedMode)) {
            AiLog.LOG.warn("[numen-econ] 未知 mode '{}' → 按 shadow 执行", requestedMode);
        }
        int window = (int) clamp(longOr(root, "windowMinutes", 60), 5, 1440);
        int cooldown = (int) clamp(longOr(root, "adviceCooldownMinutes", 5), 1, 60);
        Map<String, Limit> roles = new LinkedHashMap<>();
        JsonElement rolesEl = root.get("roles");
        if (rolesEl != null && rolesEl.isJsonObject()) {
            for (var e : rolesEl.getAsJsonObject().entrySet()) {
                JsonElement v = e.getValue();
                if (!v.isJsonObject()) {
                    AiLog.LOG.warn("[numen-econ] 角色 '{}' 的限额不是对象，已忽略", e.getKey());
                    continue;
                }
                JsonObject o = v.getAsJsonObject();
                for (String key : o.keySet()) {
                    if (!key.equals("tokensPerWindow") && !key.equals("callsPerWindow")
                            && !key.equals("minutesPerWindow")) {
                        AiLog.LOG.warn("[numen-econ] 角色 '{}' 含未知字段 '{}'（拼错的字段会被当成不限！）",
                                e.getKey(), key);
                    }
                }
                roles.put(e.getKey(), new Limit(
                        Math.max(0, longOr(o, "tokensPerWindow", 0)),
                        Math.max(0, longOr(o, "callsPerWindow", 0)),
                        Math.max(0, longOr(o, "minutesPerWindow", 0))));
            }
        }
        return new Policy(version, mode, window, cooldown, Map.copyOf(roles));
    }

    private static void writeExampleIfMissing(Path configDir) {
        try {
            Path example = configDir.resolve("budget-policy.example.json");
            if (Files.exists(example)) return;
            Files.createDirectories(configDir);
            Files.writeString(example, EXAMPLE_JSON, StandardCharsets.UTF_8);
        } catch (Exception ex) {
            AiLog.LOG.warn("[numen-econ] 写示例文件失败（不影响运行）: {}", ex.toString());
        }
    }

    private static String describeLimits(Policy p) {
        if (p.roles().isEmpty()) return "（无）";
        StringBuilder sb = new StringBuilder();
        p.roles().forEach((role, lim) -> {
            if (sb.length() > 0) sb.append("; ");
            sb.append(role).append("(tokens=").append(lim.tokensPerWindow())
                    .append(",calls=").append(lim.callsPerWindow())
                    .append(",minutes=").append(lim.minutesPerWindow()).append(")");
        });
        return sb.toString();
    }

    private static long longOr(JsonObject o, String key, long def) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) return def;
        try {
            return el.getAsLong();
        } catch (RuntimeException ex) {
            AiLog.LOG.warn("[numen-econ] 字段 '{}' 不是数字，按默认 {} 处理", key, def);
            return def;
        }
    }

    private static String strOr(JsonObject o, String key, String def) {
        JsonElement el = o.get(key);
        if (el == null || el.isJsonNull()) return def;
        try {
            return el.getAsString();
        } catch (RuntimeException ex) {
            AiLog.LOG.warn("[numen-econ] 字段 '{}' 不是字符串，按默认 '{}' 处理", key, def);
            return def;
        }
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    // ---- 测试缝 ----

    /** 测试用：注入时钟。 */
    static void setClockForTest(LongSupplier c) {
        clock = c;
    }

    /** 测试用：清空计量、建议冷却与已加载策略。 */
    static void resetForTest() {
        METER.clear();
        LAST_ADVICE_MS.clear();
        policy = DEFAULT_POLICY;
        loadedFrom = null;
        loadedMtimeMs = -1L;
        clock = System::currentTimeMillis;
    }

    /** 测试用：当前生效策略。 */
    static Policy policyForTest() {
        return policy;
    }

    static final String EXAMPLE_JSON = """
            {
              "version": 1,
              "mode": "shadow",
              "windowMinutes": 60,
              "adviceCooldownMinutes": 5,
              "roles": {
                "execution": { "tokensPerWindow": 0, "callsPerWindow": 0, "minutesPerWindow": 0 },
                "planning":  { "tokensPerWindow": 0, "callsPerWindow": 0, "minutesPerWindow": 0 },
                "learning":  { "tokensPerWindow": 0, "callsPerWindow": 0, "minutesPerWindow": 0 }
              },
              "_readme": [
                "0 = 不限（缺省即不限，不会给出任何建议）。",
                "tokensPerWindow 计 totalTokens（输入+输出+缓存，按模型上报）；callsPerWindow 计调用次数；minutesPerWindow 计调用耗时（分钟）。",
                "mode: shadow = 只发 budget_advice 建议事件、不拦截任何调用；enforce 本版本尚未实现（会按 shadow 执行并在日志说明）。",
                "窗口是滑动窗口（最近 windowMinutes 分钟）；计量只在内存、重启清零，跨重启对账请看 monitor 的 llm_usage 事件。",
                "角色按相位归并：execution=execution/execution_retry/goal_judging/compaction；planning=stage_a/stage_b/fallback；learning=review；其余=unknown（只计量不建策）。",
                "此文件是示例；要生效请复制为 budget-policy.json 再改（本示例不会被读取）。"
              ]
            }
            """;
}
