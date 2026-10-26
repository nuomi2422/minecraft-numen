package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.plugins.learner.core.Memo;
import com.dwinovo.numen.plugins.learner.core.MemoQueue;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1 确定性采集：3 连败 → 1 条 memo；mcp- 外部控制面不采；快照带 window_first（轨迹锚点）。
 */
class ToolFailureIntakeTest {

    private static final UUID CID = UUID.fromString("e502d17b-0000-0000-0000-000000000001");

    @TempDir
    Path tmp;

    @BeforeEach
    void reset() throws Exception {
        ToolFailureIntake.resetForTest();
        Files.createDirectories(tmp.resolve("monitor"));
    }

    @Test
    void threeNaturalFailuresProduceOneMemoWithTrajectoryAnchor() throws Exception {
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            tools.append(toolResult("tool-" + i, "call_" + i, "craft",
                    "{\"success\":false,\"message\":\"stone_pickaxe is a 3x3 recipe, needs a crafting table\"}"))
                    .append('\n');
        }
        Files.writeString(tmp.resolve("monitor/tools.jsonl"), tools.toString(), StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("monitor/state.jsonl"), state(), StandardCharsets.UTF_8);
        MemoQueue queue = new MemoQueue(tmp.resolve("queue.json"));

        int n = ToolFailureIntake.scanOnce(tmp, id -> queue);

        assertEquals(1, n, "三次同形态失败必须收成一条复盘单元，不是三条");
        assertEquals(1, queue.size());
        Memo memo = queue.drain(10).get(0);
        assertTrue(memo.id().startsWith("tf-craft-"), memo.id());
        assertTrue(memo.snapshot().contains("window_first=tool-0"),
                "快照必须带轨迹锚点（window_first），否则 TrajectorySlices 找不到切片：" + memo.snapshot());
    }

    @Test
    void mcpControlPlaneFailuresAreNotLearned() throws Exception {
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            tools.append(toolResult("mcp-" + i, "mcp-" + i, "rdd_standby",
                    "{\"success\":false,\"message\":\"cannot stand by\"}")).append('\n');
        }
        Files.writeString(tmp.resolve("monitor/tools.jsonl"), tools.toString(), StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("monitor/state.jsonl"), state(), StandardCharsets.UTF_8);
        MemoQueue queue = new MemoQueue(tmp.resolve("queue.json"));

        int n = ToolFailureIntake.scanOnce(tmp, id -> queue);

        assertEquals(0, n, "mcp- 是外部控制面，不是同伴决策");
        assertEquals(0, queue.size());
    }

    private static String toolResult(String eventId, String callId, String tool, String resultJson) {
        JsonObject o = new JsonObject();
        o.addProperty("event_id", eventId);
        o.addProperty("timestamp", Instant.now().toString());
        o.addProperty("type", "tool_result");
        JsonObject d = new JsonObject();
        d.addProperty("companion_id", CID.toString());
        d.addProperty("tool", tool);
        d.addProperty("tool_call_id", callId);
        d.addProperty("result", resultJson);
        o.add("data", d);
        return o.toString();
    }

    private static String state() {
        JsonObject o = new JsonObject();
        o.addProperty("timestamp", Instant.now().toString());
        o.addProperty("type", "companion_state");
        JsonObject d = new JsonObject();
        d.addProperty("companion_id", CID.toString());
        d.addProperty("items", 5);
        d.addProperty("effects", 0);
        o.add("data", d);
        return o.toString();
    }
}
