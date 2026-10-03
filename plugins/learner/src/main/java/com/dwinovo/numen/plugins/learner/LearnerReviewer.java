package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.llm.LlmEndpoint;
import com.dwinovo.numen.agent.llm.NumenLlmClient;
import com.dwinovo.numen.agent.llm.ConvoState;
import com.dwinovo.numen.platform.Services;
import com.dwinovo.numen.platform.services.INumenConfig;
import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.Verdict;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 学习者的「复盘」：拿一批备忘录去问 LLM，拿回结构化判定。
 *
 * <p>调用方式照抄 {@code RddDecomposer.llmAsk}（plugins/rdd/.../RddDecomposer.java:55-82）：
 * 从 {@link Services#CONFIG} 取端点 → {@link NumenLlmClient#forEndpoint} →
 * {@code chatStreaming}（无工具、纯文本，抄 EntityAgentLoop.java:1424 的用法）。
 *
 * <p><b>降级契约</b>：没配 apiKey / 端点失败 / 回复解析不出来，
 * 一律返回带 <b>ok=false</b> 的 {@link ReviewOutcome}，由工具层如实回报
 * {@code LLM_UNAVAILABLE} 或 {@code UNPARSEABLE}。
 * 绝不返回空判定冒充成功（规格 §3 质量门）。
 */
final class LearnerReviewer {

    private static final Logger LOG = LoggerFactory.getLogger(LearnerReviewer.class);

    /** 复盘结果：要么有判定且 ok=true，要么 ok=false 且带明确原因。 */
    record ReviewOutcome(boolean ok, String reason, List<Verdict> verdicts) {

        static ReviewOutcome fail(String reason) {
            return new ReviewOutcome(false, reason, List.of());
        }

        static ReviewOutcome ok(List<Verdict> verdicts) {
            return new ReviewOutcome(true, "OK", verdicts);
        }
    }

    private static final String SYSTEM_TEMPLATE = """
            你是 Numen 的「学习者」。你的唯一职责是复盘干活 AI 留下的原始备忘录，判断这些经历
            以后该不该沉淀成经验、该不该用原子编排(AC)重做一遍、要不要先做携带分级、要不要自编译出新工具，
            或者根本不需要动作。

            硬规则：
            1. 只输出 JSON，不要任何解释性文字、不要 markdown 代码块之外的内容。
            2. 每条备忘录单独输出一个对象。
            3. actions 是数组，可多选，取值只能是：
               WRITE_EXPERIENCE / USE_AC / USE_CARRIER / SELF_COMPILE / NO_ACTION / NEEDS_HUMAN
            4. 只有真正可复用的教训才给 WRITE_EXPERIENCE；一次性意外给 NO_ACTION。
            5. SELF_COMPILE 只在「明确缺少工具能力」时给；能靠现有工具解决就不要给。
            6. confidence 取 0 到 1。
            7. rewritten_query 给出 1 到 3 个用于检索经验库的改写检索词（这是替代向量检索的检索键）。
            8. ★ 经验必须是**结构化对象**，不是一段话。%s
            9. 给 WRITE_EXPERIENCE 就**必须**同时给出 experience 对象；给 NO_ACTION 时不要给。
            10. 不要输出 experience_draft 这个键（旧格式，已废弃；一段散文不算经验）。
            11. ★ experienceType 是**分类事实**，从 5 个里挑**一个**；挑不出来说明还没想清楚
            这是哪一类 → 给 NO_ACTION，**不要拿近义词顶**（分类错等于把这条经验劈成两条）。
            12. ★ 2026-10-03 补了三个载荷位。声明了就必须给，可以给空串表示「这次不写」，
            但**不许声明了又不给理由**：声明 USE_AC 必须给 ac_script_draft，
            声明 USE_CARRIER 必须给 carrier_draft，声明 SELF_COMPILE 必须给 self_compile_request。
            （以前「声明了却没地方写」——学习者说要写却交不出内容，回执里也没线索说明它本该写什么。）
            13. 同一条记录可以**同时**给多种产物（如既 WRITE_EXPERIENCE 又 USE_AC）。
            它们是**并列**的，不是流水线（架构 owner 2026-10-03 明确）。
            14. self_compile_request 写的是**现象 + 最小复现 + 环境快照**（结构化待办），
            不是写代码 —— 下游不自动写码，由外层工程流接手（B11）。

            输出格式：
            {"verdicts":[{"memo_id":"...","actions":["WRITE_EXPERIENCE"],"confidence":0.7,
              "reasoning":"...","experience":{"mechanism":"...","preconditions":"...",
              "failureConditions":"...","observableSignal":"...","derivation":"...",
              "efficiency":"...","evidence":"...","experienceType":"FAILURE"},"ac_script_draft":"",
              "carrier_draft":"","self_compile_request":"","rewritten_query":["..."]}]}
            """;

    // 2026-10-01：把七字段格式说明（%s）填进 SYSTEM。**格式不要求，模型就不会给** ——
    // V4 实机失败的直接原因就是 prompt 里只说了 "experience_draft":"..."，
    // 模型于是交了一段散文。这里把格式说明作为 prompt 的一部分显式注入。
    private static final String SYSTEM = SYSTEM_TEMPLATE
            .formatted(com.dwinovo.numen.plugins.learner.core.Experience.promptSpec());

    private LearnerReviewer() {}

    /**
     * 复盘一批备忘录。<b>不带</b>上轮回顾的入口（见 {@link #withPriorRound}）。
     *
     * <p>不阻塞调用线程：返回的 future 完成后才算完。失败时
     * {@link ReviewOutcome#ok} 为 false，队列条目应被 restore。
     */
    static CompletableFuture<ReviewOutcome> review(List<Memo> memos, int timeoutSeconds) {
        return withPriorRound(memos, null, timeoutSeconds);
    }

    /**
     * 复盘一批备忘录，<b>并把上轮产出摆进 prompt</b>。
     *
     * <p>架构 owner 2026-10-03 第 3 条 + 答 (a)：「循环由 AI 自己在下一轮
     * {@code learner_review} 里看着上轮结果再改」。那「上轮结果」就得在 prompt 里看得见 ——
     * 否则每轮都被当成第一次见面，改的其实是不同的东西。
     *
     * <p>★ <b>两段文本的先后是有意的</b>：上轮回顾放在<b>本轮备忘录之前</b>。
     * 放后面会被本轮那批备忘录的细节冲淡，而它恰恰是本轮判断的<b>参照系</b>
     * （「这条跟上轮那条说的是同一件事吗」这个问题，只在先看到上轮时才问得出来）。
     *
     * @param prior 上轮回顾；{@code null} 或「没有上轮」都走同一句明确的话，
     *              <b>不留空</b> —— 复盘方必须知道「没有上轮」是一条结论，不是消息漏了
     */
    static CompletableFuture<ReviewOutcome> withPriorRound(List<Memo> memos,
                                                           com.dwinovo.numen.plugins.learner.core.PriorRound.Summary prior,
                                                           int timeoutSeconds) {
        INumenConfig cfg;
        try {
            cfg = Services.CONFIG;
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(ReviewOutcome.fail("CONFIG_UNAVAILABLE: " + t));
        }
        if (cfg == null || cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            return CompletableFuture.completedFuture(ReviewOutcome.fail("LLM_UNAVAILABLE: no apiKey configured"));
        }

        // ★ 拼装下沉到 core.PriorRound：这条要能用单测钉住（见那边的方法注释）。
        //   本方法只负责拿到配置、发出请求、解析回复。
        String user = com.dwinovo.numen.plugins.learner.core.PriorRound.buildUserPrompt(memos, prior);

        LlmEndpoint ep = new LlmEndpoint(cfg.getProvider(), cfg.getModel(), cfg.getApiKey(),
                cfg.getBaseUrl(), cfg.getProxy(), "auto");

        String system = SYSTEM;
        try {
            return NumenLlmClient.forEndpoint(ep)
                    .chatStreaming(List.of(new ConvoState.Msg.User(user)),
                            List.of(), system, null)
                    .orTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
                    .handle((res, err) -> {
                        if (err != null) {
                            LOG.warn("[learner] LLM 复盘失败: {}", err.toString());
                            return ReviewOutcome.fail("LLM_FAILED: " + err);
                        }
                        String text = res.turn() == null ? null : res.turn().content();
                        if (text == null || text.isBlank()) {
                            return ReviewOutcome.fail("LLM_EMPTY: empty turn content");
                        }
                        return parseAll(memos, text);
                    });
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(ReviewOutcome.fail("LLM_DISPATCH_FAILED: " + t));
        }
    }

    /** 把 LLM 的单个 JSON 回复拆成每条 memo 的判定。 */
    private static ReviewOutcome parseAll(List<Memo> memos, String rawText) {
        String cleaned = stripFence(rawText.trim());
        try {
            var root = com.google.gson.JsonParser.parseString(cleaned);
            if (!root.isJsonObject() || !root.getAsJsonObject().has("verdicts")) {
                // 兜底：模型可能直接输出单个 verdict 对象（只在这一条 memo 时才认）
                return parseSingle(memos, cleaned);
            }
            var arr = root.getAsJsonObject().getAsJsonArray("verdicts");
            java.util.Set<String> known = new java.util.HashSet<>();
            for (Memo m : memos) {
                known.add(m.id());
            }
            List<Verdict> verdicts = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (var e : arr) {
                // P1 修复：memo_id 由 Verdict.parse 从 JSON 里读；空 id 直接判为不合法，
                // 绝不再用 "?" 占位把「同一份判定」复制给整批。
                Verdict v = Verdict.parse("", e.toString());
                if (v == null) {
                    continue;
                }
                if (!known.contains(v.memoId())) {
                    // 陌生 id（模型编的）不采纳
                    continue;
                }
                if (!seen.add(v.memoId())) {
                    // 同一条 memo 重复判定：只认第一次
                    continue;
                }
                verdicts.add(v);
            }
            if (verdicts.isEmpty()) {
                return ReviewOutcome.fail("UNPARSEABLE: no valid verdict with a known memo_id");
            }
            return ReviewOutcome.ok(verdicts);
        } catch (RuntimeException e) {
            return ReviewOutcome.fail("UNPARSEABLE: " + e);
        }
    }

    private static ReviewOutcome parseSingle(List<Memo> memos, String cleaned) {
        if (memos.size() != 1) {
            // 批量请求不接受「一份单条判定」冒充整批
            return ReviewOutcome.fail("UNPARSEABLE: single verdict returned for a batch of "
                    + memos.size());
        }
        Verdict v = Verdict.parse("", cleaned);
        if (v == null || !v.memoId().equals(memos.get(0).id())) {
            return ReviewOutcome.fail("UNPARSEABLE: single verdict memo_id does not match the memo");
        }
        return ReviewOutcome.ok(List.of(v));
    }

    private static String stripFence(String s) {
        if (!s.startsWith("```")) {
            return s;
        }
        int nl = s.indexOf('\n');
        if (nl < 0) {
            return s;
        }
        int last = s.lastIndexOf("```");
        return last <= nl ? s.substring(nl + 1) : s.substring(nl + 1, last).trim();
    }
}
