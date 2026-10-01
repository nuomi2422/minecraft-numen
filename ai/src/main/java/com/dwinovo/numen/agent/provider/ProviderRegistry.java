package com.dwinovo.numen.agent.provider;

import com.dwinovo.numen.ai.AiLog;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The LLM "sites" registry: per site an OpenAI-compatible base URL, optional
 * custom headers, and a list of known models with their context window (tokens). Single source of truth
 * for the settings dropdowns and the per-model auto-compaction threshold.
 *
 * <p>只读，只来自内置的 {@code /numen_providers.json}。它是【协议方言】目录，不是用户配置：
 * 一个条目 = 一种接口协议 + 它的默认地址/报头/思考字段 + 几个预设模型的上下文长度。
 * 这些都是代码里真有分支的东西，改了也没用。
 *
 * <p>用户想接自己的站点，填的是面板里的 baseUrl + apiKey + 模型名（存在
 * {@code ProviderLibrary} 的 {@code config/numen/providers.json}），协议从这份目录里选一个。
 * <p>这份目录<b>只读内置</b>，一个字都不往 {@code providers.json} 里写：两个类抢同一个
 * 文件的话，面板一存就把它写成 {@code entries} 形状，这边每次启动都解析失败、报一条
 * ERROR 再退回内置。
 */
public final class ProviderRegistry {

    /** {@code temperature} null = 不发(吃服务器默认);{@code maxTokens} 0 = 不发/协议默认。 */
    public record Model(String id, int ctx, boolean reasoning,
                        Double temperature, int maxTokens, int replayWindowTokens) {
        /** 兼容旧构造:不指定 replayWindowTokens 时用 {@link #DEFAULT_REPLAY_WINDOW_TOKENS}。 */
        public Model(String id, int ctx, boolean reasoning, Double temperature, int maxTokens) {
            this(id, ctx, reasoning, temperature, maxTokens, DEFAULT_REPLAY_WINDOW_TOKENS);
        }
    }
    public record Provider(String id, String name, String baseUrl, boolean custom,
                           Map<String, String> headers, List<Model> models,
                           String thinkingFormat, String protocol) {}

    /** Fallback context window for an unknown model (e.g. a custom one). */
    public static final int DEFAULT_CTX = 64_000;

    /**
     * 每轮发给模型的历史窗口上限(tokens),未配置时用这个值。
     *
     * <p><b>这是策略,不是能力</b> —— 与 {@link #DEFAULT_CTX} 分开的原因同源:
     * 模型能吃多少是给定的,我们每轮发多少是可以调的。
     * 2026-10-01 第 3 轮把它从写死常量提成配置项,
     * 免得把一个「当时测出来的数」当成「最优值」留给下一个人。
     *
     * <p><b>★ 标称 20,000 的真实含义（2026-10-01 实测，别再把它当成「就是 2 万 token」）</b>：
     * <pre>
     *   计价含 P95 安全系数 1.5（见 CompactSplit.LIMIT_SAFETY_FACTOR）
     *     → 标称 20,000 的「历史窗口」，真实发出的历史流水约 13,300 token
     *   且**不含** system(约 12,938 字符) + tools 声明(约 11,913 字符) + runtime_state
     *     这三样每轮现算挂载，不进 history 预算，合计约 8,000 token 固定开销
     *   实测稳态 promptTokens = 26,162
     *     （第 2 轮未校准时同一份代码实测最大 117,379）
     * </pre>
     * <b>所以「20000」既不是 20,000 也不是 13,300，是「流水窗口 + 约 8,000 固定」。</b>
     *
     * <p><b>配置写在哪（2026-10-01 核实，别写错地方）</b>：
     * 本类读的是 <b>classpath 内置</b> {@code /numen_providers.json}
     * （{@code ai/src/main/resources/numen_providers.json}），
     * <b>不是</b>用户目录 {@code config/numen/providers.json}
     * —— 后者由 {@code ProviderLibrary} 读，schema 是 {@code entries[]} 平铺，
     * <b>在这里写不会生效</b>。{@code ctx} 同样只认内置 catalog。
     */
    public static final int DEFAULT_REPLAY_WINDOW_TOKENS = 20_000;

    private static final List<Provider> PROVIDERS = load();

    private ProviderRegistry() {}

    private static List<Provider> load() {
        return List.copyOf(parse(readBundled()));
    }

    private static String readBundled() {
        try (var in = ProviderRegistry.class.getResourceAsStream("/numen_providers.json")) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            AiLog.LOG.error("[numen] numen_providers.json not readable", e);
            return null;
        }
    }

    private static List<Provider> parse(String json) {
        List<Provider> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JsonObject root = JsonParser.parseReader(new InputStreamReader(
                    new java.io.ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            for (var pe : root.getAsJsonArray("providers")) {
                JsonObject p = pe.getAsJsonObject();
                List<Model> models = new ArrayList<>();
                if (p.has("models")) {
                    for (var me : p.getAsJsonArray("models")) {
                        JsonObject m = me.getAsJsonObject();
                        models.add(new Model(
                                m.get("id").getAsString(),
                                m.has("ctx") ? m.get("ctx").getAsInt() : DEFAULT_CTX,
                                m.has("reasoning") && m.get("reasoning").getAsBoolean(),
                                m.has("temperature") ? m.get("temperature").getAsDouble() : null,
                                m.has("maxTokens") ? m.get("maxTokens").getAsInt() : 0,
                                m.has("replayWindowTokens") ? m.get("replayWindowTokens").getAsInt()
                                        : DEFAULT_REPLAY_WINDOW_TOKENS));
                    }
                }
                Map<String, String> headers = new LinkedHashMap<>();
                if (p.has("headers")) {
                    for (var h : p.getAsJsonObject("headers").entrySet()) {
                        headers.put(h.getKey(), h.getValue().getAsString());
                    }
                }
                out.add(new Provider(
                        p.get("id").getAsString(),
                        p.get("name").getAsString(),
                        p.has("baseUrl") ? p.get("baseUrl").getAsString() : "",
                        p.has("custom") && p.get("custom").getAsBoolean(),
                        Map.copyOf(headers),
                        List.copyOf(models),
                        p.has("thinkingFormat") ? p.get("thinkingFormat").getAsString() : "",
                        p.has("protocol") ? p.get("protocol").getAsString() : ""));
            }
        } catch (Exception e) {
            AiLog.LOG.error("[numen] failed to parse numen_providers.json", e);
        }
        return out;
    }

    public static List<Provider> providers() { return PROVIDERS; }

    /** Provider by id (config aliases resolved), or the first one (or null if the registry is empty). */
    public static Provider provider(String id) {
        String c = canon(id);
        for (Provider p : PROVIDERS) {
            if (p.id().equals(c)) return p;
        }
        return PROVIDERS.isEmpty() ? null : PROVIDERS.get(0);
    }

    /** True iff {@code id} (alias-resolved) names a real registered site — no first-entry fallback. */
    public static boolean has(String id) {
        String c = canon(id);
        for (Provider p : PROVIDERS) if (p.id().equals(c)) return true;
        return false;
    }

    /** OpenAI-compatible base URL for a site (empty if unknown). */
    public static String baseUrl(String providerId) {
        Provider p = provider(providerId);
        return p == null ? "" : p.baseUrl();
    }

    /**
     * 站点别名 → 规范 id 的**唯一真源**(kimi/doubao/qwen/glm/silicon 这些玩家
     * 顺手会填的名字)。装配({@code NumenLlmClient.pickProvider})与本类的查询
     * 都从这里走——别名表只此一张,别处不得复刻。
     */
    public static String canonicalId(String id) {
        if (id == null) return "openai";
        return switch (id.toLowerCase()) {
            case "kimi" -> "moonshot";
            case "doubao", "ark" -> "volcengine";
            case "qwen", "tongyi", "aliyun" -> "dashscope";
            case "glm" -> "zhipu";
            case "silicon" -> "siliconflow";
            case "openai-compatible" -> "openai";
            default -> id.toLowerCase();
        };
    }

    private static String canon(String id) {
        return canonicalId(id);
    }

    /** Custom request headers for a site (empty if none). */
    public static Map<String, String> headers(String providerId) {
        Provider p = provider(providerId);
        return p == null ? Map.of() : p.headers();
    }

    /** 站点的思考开关方言(空 = 默认 effort 形态,见 {@code LlmProvider.THINKING_*})。 */
    public static String thinkingFormat(String providerId) {
        Provider p = provider(providerId);
        return p == null ? "" : p.thinkingFormat();
    }

    /** 站点的线协议(空 = openai 兼容;{@code "anthropic"} = Anthropic Messages)。 */
    public static String protocol(String providerId) {
        Provider p = provider(providerId);
        return p == null ? "" : p.protocol();
    }

    /** Context window for a (provider, model) pair, or {@link #DEFAULT_CTX} if unknown / custom. */
    public static int contextWindow(String providerId, String modelId) {
        Model m = model(providerId, modelId);
        return m == null ? DEFAULT_CTX : m.ctx();
    }

    /**
     * 每轮发给模型的历史窗口上限(tokens)。
     *
     * <p>值 &lt;= 0 一律回落到 {@link #DEFAULT_REPLAY_WINDOW_TOKENS}
     * —— 配错不该让窗口变成 0(那等于让 AI 失明,见 B21)。
     */
    public static int replayWindowTokens(String providerId, String modelId) {
        Model m = model(providerId, modelId);
        int v = (m == null) ? DEFAULT_REPLAY_WINDOW_TOKENS : m.replayWindowTokens();
        return v > 0 ? v : DEFAULT_REPLAY_WINDOW_TOKENS;
    }

    /** (provider, model) 的注册条目,查无(自定义/未收录)为 null。生成参数从这里取。 */
    public static Model model(String providerId, String modelId) {
        Provider p = provider(providerId);
        if (p != null && modelId != null) {
            for (Model m : p.models()) {
                if (m.id().equals(modelId)) return m;
            }
        }
        return null;
    }
}
