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
        String result = "{\"success\":" + ok + ",\"message\":\"" + msg + "\"}";
        return "{\"event_id\":\"" + eventId + "\",\"type\":\"tool_result\",\"timestamp\":\"" + t
                + "\",\"data\":{\"companion_id\":\"c1\",\"tool\":\"" + tool
                + "\",\"tool_call_id\":\"call_1\",\"result\":\"" + result.replace("\"", "\\\"") + "\"}}";
    }

    private static String state(Instant t, int items, int effects) {
        return "{\"type\":\"companion_state\",\"timestamp\":\"" + t
                + "\",\"data\":{\"companion_id\":\"c1\",\"items\":" + items
                + ",\"effects\":" + effects + "}}";
    }
}
