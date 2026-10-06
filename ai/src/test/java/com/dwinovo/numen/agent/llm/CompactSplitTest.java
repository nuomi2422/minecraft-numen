package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.AssistantTurn;
import com.dwinovo.numen.agent.provider.LlmToolCall;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 压缩切分的回归钉:切点只能落在 User(首选)或 Assistant(劈轮),工具结果永远
 * 跟着它的调用;预算装得下整段时不切;一条都装不下时保留段为空(退化全量总结)。
 * 兜底窗口（调用方在空段时用）见 {@link CompactSplit#validTail}：
 * 绝不以 Tool 开头 —— 2026-10-06 实机 400 的根因就是它。
 */
class CompactSplitTest {

    /**
     * 造一条「<b>计价器认为</b>约 {@code approxTokens} token」的消息。
     *
     * <p>2026-10-01 第 3 轮：计价口径校准后（{@code CompactSplit.LIMIT_SAFETY_FACTOR=1.5}），
     * 同样的字符数会被计出 1.5 倍。这里按新口径反推字符数，
     * <b>让本文件的断言与「刀口落在哪一条」保持原意不被计价器改动带偏</b>。
     */
    private static int charsFor(int approxTokens) {
        return Math.max(0, (int) Math.round((approxTokens - 8) / CompactSplit.LIMIT_SAFETY_FACTOR));
    }

    private static ConvoState.Msg user(int approxTokens) {
        return new ConvoState.Msg.User("字".repeat(charsFor(approxTokens)));
    }

    private static ConvoState.Msg assistant(int approxTokens) {
        return new ConvoState.Msg.Assistant(new AssistantTurn(
                "字".repeat(charsFor(approxTokens)), List.of(), null));
    }

    private static ConvoState.Msg assistantWithCall(String id) {
        return new ConvoState.Msg.Assistant(new AssistantTurn(
                "", List.of(new LlmToolCall(id, "goto", "{}")), null));
    }

    private static ConvoState.Msg tool(String id, int approxTokens) {
        return new ConvoState.Msg.Tool(id, "字".repeat(Math.max(0, approxTokens - 8)));
    }

    @Test
    void cutsAtTheEarliestUserBoundaryWithinBudget() {
        List<ConvoState.Msg> h = List.of(
                user(100), assistant(100),          // 旧轮:应被总结
                user(50), assistant(50),            // 新轮:预算内,原文保留
                user(50), assistant(50));
        var split = CompactSplit.byRecentBudget(h, 220);
        assertEquals(2, split.toSummarize().size());
        assertEquals(4, split.kept().size());
        assertTrue(split.kept().get(0) instanceof ConvoState.Msg.User);
    }

    @Test
    void toolResultNeverLeadsTheKeptSpan() {
        // 单轮超预算:User 边界装不下,劈轮落在 Assistant 上;工具结果跟着它的调用
        List<ConvoState.Msg> h = List.of(
                user(500),
                assistantWithCall("a"), tool("a", 40),
                assistantWithCall("b"), tool("b", 40),
                assistant(40));
        var split = CompactSplit.byRecentBudget(h, 150);
        assertFalse(split.kept().isEmpty());
        assertTrue(split.kept().get(0) instanceof ConvoState.Msg.Assistant,
                "劈轮点必须是 Assistant,不能把 Tool 拆成保留段的第一条");
        // 保留段里的每个 Tool,它的调用都在保留段里
        for (int i = 0; i < split.kept().size(); i++) {
            if (split.kept().get(i) instanceof ConvoState.Msg.Tool t) {
                boolean callKept = split.kept().stream()
                        .filter(m -> m instanceof ConvoState.Msg.Assistant)
                        .map(m -> ((ConvoState.Msg.Assistant) m).turn())
                        .anyMatch(turn -> turn.toolCalls().stream()
                                .anyMatch(c -> c.id().equals(t.toolCallId())));
                assertTrue(callKept, "orphan tool result in kept span: " + t.toolCallId());
            }
        }
    }

    @Test
    void everythingFitsMeansNothingToSummarize() {
        List<ConvoState.Msg> h = List.of(user(50), assistant(50));
        var split = CompactSplit.byRecentBudget(h, 10_000);
        assertTrue(split.toSummarize().isEmpty());
        assertEquals(2, split.kept().size());
    }

    @Test
    void nothingFitsMeansSummarizeEverything() {
        List<ConvoState.Msg> h = List.of(user(500), assistant(500), user(500));
        var split = CompactSplit.byRecentBudget(h, 100);
        assertTrue(split.kept().isEmpty());
        assertEquals(3, split.toSummarize().size());
    }

    /**
     * ★ 2026-10-06 实机 400 根因回归：兜底窗口绝不许以 Tool 开头。
     *
     * <p>{@code find_tools} 展开块可单条超预算 → kept 空 → 旧兜底只保最后一条 = 大 Tool
     * → 请求 [system, Tool, user] → 端点 400 且重试同样 400（live context.jsonl 实测
     * roles=stu、assistantIdx=-1）。
     */
    @Test
    void tailFallbackNeverStartsWithATool() {
        List<ConvoState.Msg> h = List.of(user(100), assistantWithCall("x"), tool("x", 5000));
        var split = CompactSplit.byRecentBudget(h, 100);
        assertTrue(split.kept().isEmpty(), "设计如此：一条都装不下 → 交给调用方兜底");

        var tail = CompactSplit.validTail(h);
        assertFalse(tail.isEmpty(), "不能返回空（那等于让 AI 失忆）");
        assertTrue(tail.get(0) instanceof ConvoState.Msg.Assistant,
                "兜底必须带上工具结果的调用方，绝不能以 Tool 开头：" + tail);
        assertEquals(2, tail.size(), "assistant 调用 + 它的工具结果");

        // 连续多条工具结果：一路退到调用方
        List<ConvoState.Msg> multi = List.of(user(100), assistantWithCall("x"),
                tool("x", 10), tool("x", 10));
        var tail2 = CompactSplit.validTail(multi);
        assertTrue(tail2.get(0) instanceof ConvoState.Msg.Assistant);

        // 整段历史都是 Tool（坏数据）→ 宁可不带，也不发必然 400 的请求
        assertEquals(0, CompactSplit.validTail(List.of(tool("a", 10))).size());
        assertEquals(0, CompactSplit.validTail(List.of()).size());
    }

    @Test
    void estimatorCountsCjkHeavierThanAscii() {
        var cjk = new ConvoState.Msg.User("字".repeat(400));
        var ascii = new ConvoState.Msg.User("a".repeat(400));
        // 2026-10-01 第 3 轮：老口径 ascii/4 让 CJK:ASCII ≈ 4:1，这里原来断言 > 3×。
        // 校准后散文类 ASCII 密度实测为 0.80 token/字符（867 对实测拟合），
        // CJK 仍是 1.0 → 比值降到 1.25。**「3×」是老口径的产物，不是设计意图**；
        // 真正的意图是「CJK 不能被当成和散文一样便宜」，所以改成断言权重的真实比值。
        assertTrue(CompactSplit.estimateTokens(cjk) > CompactSplit.estimateTokens(ascii) * 1.2,
                "CJK 每字约 1 token,散文类 ASCII 约 0.80 token/字符（实测拟合）");
        // 反向也要钉住：别哪天把散文权重又调回 0.25
        assertTrue(CompactSplit.estimateTokens(ascii) * 2 > CompactSplit.estimateTokens(cjk),
                "散文类 ASCII 不应比 CJK 贵一倍以上");
        // 列表求和 = 逐条之和
        var list = new ArrayList<ConvoState.Msg>(List.of(cjk, ascii));
        assertEquals(CompactSplit.estimateTokens(cjk) + CompactSplit.estimateTokens(ascii),
                CompactSplit.estimateTokens(list));
    }
}
