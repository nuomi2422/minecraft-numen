package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 轨迹组装：anchor → 前后窗口 → 工具/状态行；读不到必须明说，不编造。 */
class TrajectorySlicesTest {

    @TempDir
    Path dir;

    @Test
    void assemblesAllToolRowsInWindowAndPrePostState() throws Exception {
        Instant t = Instant.parse("2026-10-26T10:00:00Z");
        writeInstrumentation("instr-1", t);
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            tools.append(tool(t, "tool_" + i, i % 3 == 0 ? false : true, "m" + i)).append('\n');
        }
        Files.writeString(dir.resolve("monitor/tools.jsonl"), tools.toString(), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("monitor/state.jsonl"),
                state(t.minusSeconds(10), 3, 1) + "\n" + state(t.plusSeconds(10), 1, 0) + "\n",
                StandardCharsets.UTF_8);

        String text = TrajectorySlices.forMemo(dir, null,
                new Memo("ai-instr-1", "p", "death", "t", "", 0L));

        assertTrue(text.contains("锚点 instr-1"), text);
        assertTrue(text.contains("tool_9"), "十次调用都应进入切片，不只前 6 条：" + text);
        assertTrue(text.contains("FAILED"), text);
        assertTrue(text.contains("items=3") && text.contains("items=1"), "前置/后置状态都要在：" + text);
        assertTrue(text.contains("→"), "前后状态不同时应显示箭头：" + text);
    }

    @Test
    void tfAnchorUsesWindowFirstEvent() throws Exception {
        Instant t = Instant.parse("2026-10-26T10:00:00Z");
        Files.createDirectories(dir.resolve("monitor"));
        Files.writeString(dir.resolve("monitor/tools.jsonl"),
                tool(t, "tools-2", "craft", false, "3x3 recipe") + "\n", StandardCharsets.UTF_8);

        String text = TrajectorySlices.forMemo(dir, null,
                new Memo("tf-craft-abc-tools-2", "p", "tool_failure", "t",
                        "items=1, effects=0, tool=craft, class=craft_needs_table, window_first=tools-2, window_last=tools-2", 0L));

        assertTrue(text.contains("锚点 tools-2"), text);
        assertTrue(text.contains("craft"), text);
    }

    /**
     * 2026-11-03（实机 death61）：mcp-* 是外部 MCP/控制面轮询，不是同伴的动作。
     * 不过滤会让复盘写出「死前只做过 task_status/get_self_status 轮询」这种假证据，
     * 并跟着经验进执行视图（已发生：一条 TRIAL 条目被 53/55 次呈现）。
     */
    @Test
    void mcpControlPlaneCallsAreNotCompanionActions() throws Exception {
        Instant t = Instant.parse("2026-11-02T16:06:30Z");
        writeInstrumentation("instr-9", t);
        Files.writeString(dir.resolve("monitor/tools.jsonl"),
                tool(t, "tools-a", "call_00_real", "get_self_status", true, "hp=20") + "\n"
                        + tool(t, "tools-b", "mcp-31", "task_status", true, "身体空闲") + "\n",
                StandardCharsets.UTF_8);

        String text = TrajectorySlices.forMemo(dir, null,
                new Memo("ai-instr-9", "p", "death", "t", "", 0L));

        assertTrue(text.contains("get_self_status"), "同伴自己的调用必须保留：" + text);
        assertTrue(!text.contains("[外部控制台] get_self_status"), "同伴调用不许被标成外部：" + text);
        assertTrue(text.contains("[外部控制台] task_status"),
                "mcp-* 要保留但必须标明是外部控制台（它会影响结局，是归因材料）：" + text);
        assertTrue(!text.contains("读不到 ≠ 没发生"), "还有调用时不能报'没有工具调用'：" + text);
    }

    /**
     * 2026-11-03：find_tools 的 client-local 行是 `success:"UNKNOWN", result:""`。
     * 旧代码默认 ok=true，把它渲染成 "find_tools ok" —— UNKNOWN 被写成成功（F1/F2 同族）。
     * 现在缺成败证据必须显示 "?"，不许猜。
     */
    @Test
    void unknownOutcomeIsNotRenderedAsOk() throws Exception {
        Instant t = Instant.parse("2026-11-02T17:19:00Z");
        writeInstrumentation("instr-u", t);
        String row = "{\"event_id\":\"tools-u\",\"type\":\"tool_result\",\"timestamp\":\"" + t
                + "\",\"data\":{\"companion_id\":\"c1\",\"tool\":\"find_tools\","
                + "\"tool_call_id\":\"call_u\",\"journaled_by\":\"client-local\","
                + "\"success\":\"UNKNOWN\",\"result\":\"\"}}";
        Files.writeString(dir.resolve("monitor/tools.jsonl"), row + "\n", StandardCharsets.UTF_8);

        String text = TrajectorySlices.forMemo(dir, null,
                new Memo("ai-instr-u", "p", "tool_failure", "t", "", 0L));

        assertTrue(text.contains("find_tools ?"), "UNKNOWN 必须显示 ?，不能写成 ok：" + text);
        assertTrue(!text.contains("find_tools ok"), "不许把 UNKNOWN 说成成功：" + text);
    }

    /**
     * 2026-11-03（实机 r5-3）：重试预算是分钟级的，±60s 会把决定性动作切在窗外
     * （18:11:59 的 loop 事件，chest 巡查在 18:10:48 —— 差 11 秒被漏）。
     * loop_detected 的前置窗口放宽到 10 分钟。
     */
    @Test
    void loopMemosSeeTheWholeRetryWindow() throws Exception {
        Instant t = Instant.parse("2026-11-02T18:11:59Z");
        writeInstrumentation("instr-loop", t);
        Files.writeString(dir.resolve("monitor/tools.jsonl"),
                tool(t.minusSeconds(300), "tools-early", "call_00_e", "inspect_block", true, "chest found") + "\n"
                        + tool(t.minusSeconds(5), "tools-late", "call_00_l", "report_task_concern", false,
                                "concern rejected: expected_subtask_mismatch") + "\n",
                StandardCharsets.UTF_8);

        String text = TrajectorySlices.forMemo(dir, null,
                new Memo("ai-instr-loop", "p", "loop_detected", "t", "", 0L));

        assertTrue(text.contains("inspect_block"), "5 分钟前的尝试必须进切片（旧 ±60s 会漏）：" + text);
        assertTrue(text.contains("rejected"), "最后那条失败也要在：" + text);
        assertTrue(text.contains("-600s"), "窗口必须如实写出来：" + text);
    }

    /**
     * 2026-11-03（实机 r3-5：39 条动作）：窗口放宽到 10 分钟后，旧代码"凑满 24 条就 break"
     * 留下的是**最早**的 24 条，最后那条决定性失败反而被切掉。必须保留最近 24 条。
     */
    @Test
    void keepsTheMostRecentRowsWhenWindowIsFull() throws Exception {
        Instant t = Instant.parse("2026-11-02T18:11:59Z");
        writeInstrumentation("instr-full", t);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            sb.append(tool(t.minusSeconds(600 - i * 10), "tools-" + i, "call_" + i, "tool_" + i, i != 39, "m" + i)).append('\n');
        }
        Files.writeString(dir.resolve("monitor/tools.jsonl"), sb.toString(), StandardCharsets.UTF_8);

        String text = TrajectorySlices.forMemo(dir, null,
                new Memo("ai-instr-full", "p", "loop_detected", "t", "", 0L));

        assertTrue(text.contains("tool_39"), "最近一条必须在（旧实现会被切掉）：" + text);
        assertTrue(!text.contains("tool_0]"), "最早的应被丢掉，避免把窗口开头当结局：" + text);
        long rows = text.lines().filter(l -> l.startsWith("  · ")).count();
        assertTrue(rows <= 24, "最多 24 行，实得 " + rows);
    }

    @Test
    void missingAnchorIsExplicitlyNotFabricated() throws Exception {
        Files.createDirectories(dir.resolve("monitor"));
        Files.writeString(dir.resolve("monitor/instrumentation.jsonl"), "", StandardCharsets.UTF_8);
        String text = TrajectorySlices.forMemo(dir, null,
                new Memo("ai-nope", "p", "death", "t", "", 0L));
        assertTrue(text.contains("未找到（不编造）"), text);
    }

    @Test
    void emptyToolWindowSaysUnreadableNotAbsent() throws Exception {
        Instant t = Instant.parse("2026-10-26T10:00:00Z");
        writeInstrumentation("instr-3", t);
        Files.createDirectories(dir.resolve("monitor"));
        Files.writeString(dir.resolve("monitor/tools.jsonl"), "", StandardCharsets.UTF_8);
        String text = TrajectorySlices.forMemo(dir, null,
                new Memo("ai-instr-3", "p", "death", "t", "", 0L));
        assertTrue(text.contains("读不到 ≠ 没发生"), text);
    }

    private void writeInstrumentation(String id, Instant t) throws Exception {
        Files.createDirectories(dir.resolve("monitor"));
        String line = "{\"event_id\":\"" + id + "\",\"timestamp\":\"" + t + "\",\"type\":\"death\"}";
        Files.writeString(dir.resolve("monitor/instrumentation.jsonl"), line + "\n", StandardCharsets.UTF_8);
    }

    private static String tool(Instant t, String tool, boolean ok, String msg) {
        return tool(t, "tools-" + tool, tool, ok, msg);
    }

    private static String tool(Instant t, String eventId, String tool, boolean ok, String msg) {
        return tool(t, eventId, "call_1", tool, ok, msg);
    }

    private static String tool(Instant t, String eventId, String callId, String tool, boolean ok, String msg) {
        String result = "{\"success\":" + ok + ",\"message\":\"" + msg + "\"}";
        return "{\"event_id\":\"" + eventId + "\",\"type\":\"tool_result\",\"timestamp\":\"" + t
                + "\",\"data\":{\"companion_id\":\"c1\",\"tool\":\"" + tool
                + "\",\"tool_call_id\":\"" + callId + "\",\"result\":\"" + result.replace("\"", "\\\"") + "\"}}";
    }

    private static String state(Instant t, int items, int effects) {
        return "{\"type\":\"companion_state\",\"timestamp\":\"" + t
                + "\",\"data\":{\"companion_id\":\"c1\",\"items\":" + items
                + ",\"effects\":" + effects + "}}";
    }
}
