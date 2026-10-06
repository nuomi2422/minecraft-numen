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

    /**
     * 「预算内一条都装不下」时调用方的兜底窗口：取最近一条，且<b>绝不让保留段以 Tool 开头</b>。
     *
     * <p>★ 2026-10-06 实机 400 根因（live {@code context.jsonl} 逐条实测）：
     * {@code find_tools} 的展开块（{@code <functions expanded=…>}）可以<b>单条就超预算</b>，
     * 于是 {@link #byRecentBudget} 返回空段，而旧兜底「至少保最后一条」保的正是那条 Tool ⇒
     * 请求变成 {@code [system, Tool, user]}（roles=stu、整份日志里找不到该 tool 的
     * assistant 调用，assistantIdx=-1）⇒ 端点 <b>HTTP 400</b>，同 payload 重试同样 400。
     * 实机时间线：15:39 / 18:51 / 19:00 / 19:03 / 19:08 与重启后 03:11 / 03:12 / 03:15
     * <b>循环复现</b> —— 每次模型调 {@code find_tools} 就会触发一轮。
     *
     * <p>规则：为保住这条工具结果，把它最近的调用方（Assistant）一起带上；
     * 整段历史都是 Tool（坏数据）时返回空——<b>宁可不带，也不发一个必然 400 的请求</b>。
     */
    public static List<ConvoState.Msg> validTail(List<ConvoState.Msg> all) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int start = all.size() - 1;
        while (start > 0 && all.get(start) instanceof ConvoState.Msg.Tool) {
            start--;
        }
        if (all.get(start) instanceof ConvoState.Msg.Tool) {
            return List.of();
        }
        return List.copyOf(all.subList(start, all.size()));
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
     * <b>★ P95 安全系数（限额用途必须偏保守）</b>
     *
     * <p>867 对实测的 {@code real/v2} 分位：{@code P50=0.99 P80=1.16 P90=1.23
     * P95=1.51 P99=1.69 max=1.79}。
     *
     * <p><b>为什么不取 k=1.0</b>：k=1.0 时整体很准（平均 0.98），
     * 但<b>40% 的样本低估</b>（预测/真实 &lt; 0.9）。对「账单」来说平均准就行；
     * 对<b>限额</b>来说低估是危险方向 —— 你以为没超，实际超了。
     * k=1.5 时低估降到 <b>1%</b>。
     *
     * <p><b>代价（如实说）：窗口会比标称值更紧。</b>
     * 标称 20,000 的预算，真实发出约 <b>13,300</b> token。
     * 这是「宁可少发不可超发」的取舍；需要更宽就调大 provider 的
     * {@code replayWindowTokens}（它是可配置的，别再写死）。
     */
    static final double LIMIT_SAFETY_FACTOR = 1.5;

    /**
     * <b>一条消息的 token 估（2026-10-01 第 3 轮校准）——限额计价的尺子</b>
     *
     * <p><b>老口径 {@code cjk + ascii/4} 低估 2.6×</b>。实测 867 对请求/响应
     * （live 实例 monitor 目录下 12 个 {@code context*.jsonl}，按 {@code requestId} 配对）：
     * <pre>
     *   老口径预测 / 服务端真实 promptTokens = 0.380   （99% 的样本低估）
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
     * <p><b>校准方式：按字符类别分别给权，对 867 对实测做网格拟合</b>
     * <pre>
     *   老口径 ascii/4     → 预测/真实 平均 0.380
     *   prose=0.75 punct=1 → 预测/真实 平均 0.955
     *   prose=0.80 punct=1 → 预测/真实 平均 0.986   ← 权重采用
     * </pre>
     *
     * <p>再乘 {@link #LIMIT_SAFETY_FACTOR}（P95 安全系数，见其注释）：
     * 权重拟合追求的是「整体准」，而<b>限额要的是「不低估」</b>，两件事。
     *
     * <p>⚠️ <b>已知残留：权重本身对中小载荷偏高</b>（15K–30K 字符档 2.69×、
     * 30K–60K 档 1.95×），因为那里的 CJK 占比高于全局平均。
     * 乘 1.5 之后中小载荷会<b>明显高估</b>（窗口偏紧）。
     * 这是<b>刻意取舍</b>：高估的代价是「少发一点历史」，
     * 低估的代价是「限额形同虚设」。收紧方向是安全的。
     * 校准回归见 {@code TokenEstimateCalibrationTest}。
     */
    public static int estimateTokens(ConvoState.Msg msg) {
        return (int) Math.round(tokensOf(rawTextOf(msg)) * LIMIT_SAFETY_FACTOR);
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
