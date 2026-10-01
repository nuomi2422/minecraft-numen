package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.LlmToolCall;

import java.util.List;

/**
 * 压缩的切分:把历史分成"要总结的旧段"和"原文保留的近段"。参考 pi 的做法——
 * 触发线不变,但摘要只替换旧段,最近约 {@code budget} tokens 的消息逐字跨过压缩边界,
 * 主人刚说的话和她刚给的回执不会被压成转述。
 *
 * <h2>切点规则</h2>
 * 保留段的第一条只能是 User(轮边界,首选)或 Assistant(单轮超预算时的劈轮点);
 * 永远不能是 Tool——工具结果必须跟着它的调用走,拆开的历史下一次请求就是 400。
 * 预算内找不到任何合法切点(极端:一条消息就超预算)时保留段为空,退化成全量总结。
 *
 * <h2>token 估算（2026-10-01 第 3 轮校准）</h2>
 * 与自动压缩闸门的兜底估算同一把尺(全仓唯一一份):按字符<b>类别</b>分别给权——
 * CJK 约 1 token/字、散文类 ASCII 约 0.80 token/字符、
 * <b>结构标点({@code {}[],:"} 等)约 1 token/字符</b>,每条消息记 8 token 结构开销。
 * <p>⚠️ 旧口径 {@code ascii/4}(英文散文近似)<b>实测低估 2.95×</b>——
 * 本项目 52% 的开销是工具结果 JSON,而它几乎全是结构标点。
 * 详见 {@link #estimateTokens(ConvoState.Msg)} 的实测依据。
 */
public final class CompactSplit {

    private CompactSplit() {}

    /** 切分结果:{@code toSummarize} 交给摘要请求,{@code kept} 原文保留在摘要之后。 */
    public record Split(List<ConvoState.Msg> toSummarize, List<ConvoState.Msg> kept) {}

    /**
     * 从最新往回攒,攒到 {@code budgetTokens} 为止;在预算内选<b>最早的</b>合法切点。
     * 整段历史都在预算内时 {@code toSummarize} 为空——调用方自行决定退化行为。
     */
    public static Split byRecentBudget(List<ConvoState.Msg> history, int budgetTokens) {
        int cutUser = -1;
        int cutAssistant = -1;
        long acc = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            acc += estimateTokens(history.get(i));
            if (acc > budgetTokens) {
                break;
            }
            if (history.get(i) instanceof ConvoState.Msg.User) {
                cutUser = i;
            } else if (history.get(i) instanceof ConvoState.Msg.Assistant) {
                cutAssistant = i;
            }
        }
        int cut = cutUser >= 0 ? cutUser : cutAssistant >= 0 ? cutAssistant : history.size();
        return new Split(List.copyOf(history.subList(0, cut)),
                List.copyOf(history.subList(cut, history.size())));
    }

    /** 散文类 ASCII 字符的 token 密度（2026-10-01 实测拟合值，见类注释）。 */
    static final double PROSE_TOKENS_PER_CHAR = 0.80;
    /** CJK 字符的 token 密度：约 1 字 1 token。 */
    static final double CJK_TOKENS_PER_CHAR = 1.0;
    /** 结构标点的 token 密度：JSON 里几乎各占一个 token。 */
    static final double PUNCT_TOKENS_PER_CHAR = 1.0;
    /** 每条消息的角色/结构固定开销。 */
    static final int MESSAGE_OVERHEAD_TOKENS = 8;

    /**
     * <b>一条消息的 token 估（2026-10-01 第 3 轮校准）——限额计价的尺子</b>
     *
     * <p><b>老口径 {@code cjk + ascii/4} 低估 2.95×</b>。实测 833 对请求/响应
     * （live 实例 monitor 目录下 12 个 {@code context*.jsonl}，按 {@code requestId} 配对）：
     * <pre>
     *   老口径预测 / 服务端真实 promptTokens = 0.339
     *   → 声明 20_000「token」实际发出 ≈ 59_000（实测最大 117_379）
     *   字符成分：CJK 9.2% / 散文 74.7% / 结构标点 16.2%
     * </pre>
     *
     * <p><b>根因：又是「类型对的东西用在类型错的地方」。</b>
     * {@code ascii/4} 是<b>英文散文</b>的经典近似，而本项目最大一块开销
     * （实测 52%）来自<b>工具结果 JSON</b>——它几乎全是 {@code {}[],:"} 这类结构标点，
     * 每个基本各占一个 token，密度接近 <b>1.0/字符</b>，不是 0.25。
     * 两类字符被并成同一类，<b>尺子就废了，限额就成了装饰</b>。
     *
     * <p><b>校准方式：按字符类别分别给权，对 833 对实测做网格拟合</b>
     * <pre>
     *   prose=0.75 punct=1.0 → 预测/真实 平均 0.955
     *   prose=0.80 punct=1.0 → 预测/真实 平均 0.997   ← 采用
     *   prose=0.85 punct=1.0 → 预测/真实 平均 1.040
     * </pre>
     * 取 {@code 0.80}：既贴合实测，又落在<b>略偏保守</b>的一侧
     * —— 限额宁可略高估，也别让「省 token 的手段」变成「超额的手段」。
     *
     * <p>⚠️ 剩余误差：单条样本区间仍有 {@code [0.60, 2.86]} 的离散度，主要来自
     * 「很短的请求被固定 8 token 放大」。<b>可用于限额，不可当账单。</b>
     * 校准回归见 {@code TokenEstimateCalibrationTest}。
     */
    public static int estimateTokens(ConvoState.Msg msg) {
        return tokensOf(rawTextOf(msg));
    }

    private static String rawTextOf(ConvoState.Msg msg) {
        if (msg instanceof ConvoState.Msg.User u) {
            return u.content();
        } else if (msg instanceof ConvoState.Msg.Tool t) {
            return t.content();
        } else if (msg instanceof ConvoState.Msg.Assistant a) {
            StringBuilder sb = new StringBuilder(
                    a.turn().content() == null ? "" : a.turn().content());
            for (LlmToolCall tc : a.turn().toolCalls()) {
                sb.append(tc.name()).append(tc.arguments());
            }
            return sb.toString();
        } else {
            return "";
        }
    }

    private static int tokensOf(String text) {
        if (text == null || text.isEmpty()) {
            return MESSAGE_OVERHEAD_TOKENS;
        }
        long cjk = 0, prose = 0, punct = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 0x2E7F) {
                cjk++;
            } else if (isStructural(c)) {
                punct++;
            } else {
                prose++;
            }
        }
        return (int) (cjk * CJK_TOKENS_PER_CHAR
                + Math.round(prose * PROSE_TOKENS_PER_CHAR)
                + punct * PUNCT_TOKENS_PER_CHAR
                + MESSAGE_OVERHEAD_TOKENS);
    }

    /**
     * JSON / 代码里高频、且在真实 tokenizer 下几乎各占一个 token 的结构字符。
     * 实测这类字符占全部字符的 16.2%，却是 token 密度最高的一类。
     */
    private static boolean isStructural(char c) {
        return c == '{' || c == '}' || c == '[' || c == ']' || c == '"' || c == ':'
                || c == ',' || c == '.' || c == '/' || c == '|' || c == '<' || c == '>'
                || c == '=' || c == '@' || c == '#' || c == '$' || c == '%' || c == '^'
                || c == '&' || c == '*' || c == '+' || c == ';' || c == '!' || c == '?'
                || c == '_' || c == '-' || c == '`';
    }

    /**
     * 老口径 {@code cjk + ascii/4}，<b>只供回归对比，不用于任何限额</b>。
     *
     * <p>保留它是为了让「校准到底改进了多少」有可复算的对照，
     * 而不是靠一句「我觉得更准了」。<b>新的限额代码不许调它。</b>
     */
    static int estimateTokensLegacyAsciiDiv4(ConvoState.Msg msg) {
        String text = rawTextOf(msg);
        long cjk = 0, ascii = 0;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) > 0x2E7F) cjk++; else ascii++;
            }
        }
        return (int) (cjk + ascii / 4 + MESSAGE_OVERHEAD_TOKENS);
    }

    /** 整段历史的 token 估(不含系统提示/工具表的固定开销,那份由调用方加)。 */
    public static int estimateTokens(List<ConvoState.Msg> history) {
        long sum = 0;
        for (ConvoState.Msg m : history) {
            sum += estimateTokens(m);
        }
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }
}
