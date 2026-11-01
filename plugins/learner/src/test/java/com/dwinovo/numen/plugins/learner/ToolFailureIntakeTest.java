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
import java.util.List;
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
        return toolResult(eventId, callId, tool, resultJson, null, null);
    }

    /** 可带 data.success（Boolean 或字符串）与 journaled_by，用于复现历史污染形态。 */
    private static String toolResult(String eventId, String callId, String tool, String resultJson,
                                     Object dataSuccess, String journaledBy) {
        return toolResult(eventId, callId, tool, resultJson, dataSuccess, journaledBy, null);
    }

    /** 同上，额外带 async_status（server-async 终态行）。 */
    private static String toolResult(String eventId, String callId, String tool, String resultJson,
                                     Object dataSuccess, String journaledBy, String asyncStatus) {
        JsonObject o = new JsonObject();
        o.addProperty("event_id", eventId);
        o.addProperty("timestamp", Instant.now().toString());
        o.addProperty("type", "tool_result");
        JsonObject d = new JsonObject();
        d.addProperty("companion_id", CID.toString());
        d.addProperty("tool", tool);
        d.addProperty("tool_call_id", callId);
        d.addProperty("result", resultJson);
        if (dataSuccess instanceof Boolean b) {
            d.addProperty("success", b);
        } else if (dataSuccess instanceof String s) {
            d.addProperty("success", s);
        }
        if (journaledBy != null) d.addProperty("journaled_by", journaledBy);
        if (asyncStatus != null) d.addProperty("async_status", asyncStatus);
        o.add("data", d);
        return o.toString();
    }

    // ── 2026-11-01 L1：UNKNOWN 不得被记成失败；同一调用多 writer 只算一次 ─────────

    @Test
    void clientFalseWithoutExplicitSuccessIsNotAFailure() throws Exception {
        // 实测形态：EntityAgentLoop 对无 success 字段的数据结果默认写 success=false。
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            tools.append(toolResult("tf-inj-" + i, "call_inj_" + i, "inspect_block",
                    "{\"x\":368,\"y\":67,\"z\":711,\"block\":\"minecraft:air\"}",
                    Boolean.FALSE, "client")).append('\n');
        }
        Files.writeString(tmp.resolve("monitor/tools.jsonl"), tools.toString(), StandardCharsets.UTF_8);
        MemoQueue queue = new MemoQueue(tmp.resolve("queue.json"));

        int n = ToolFailureIntake.scanOnce(tmp, id -> queue);

        assertEquals(0, n, "缺少显式 success 的数据结果不是失败；默认 false 不得变成失败事实");
        assertEquals(0, queue.size());
    }

    @Test
    void unknownStringSuccessIsNotAFailure() throws Exception {
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            tools.append(toolResult("tf-unk-" + i, "call_unk_" + i, "find_tools",
                    "<functions expanded=\"x\" />", "UNKNOWN", "client-local")).append('\n');
        }
        Files.writeString(tmp.resolve("monitor/tools.jsonl"), tools.toString(), StandardCharsets.UTF_8);
        MemoQueue queue = new MemoQueue(tmp.resolve("queue.json"));

        int n = ToolFailureIntake.scanOnce(tmp, id -> queue);

        assertEquals(0, n, "字符串 UNKNOWN 是「不知道」，不能 getAsBoolean 成 false");
        assertEquals(0, queue.size());
    }

    @Test
    void serverAsyncTerminalFailureStillCounts() throws Exception {
        // server-async 的 result 是人话 message（非 JSON），success 由任务终态显式给出 —— 仍必须算失败。
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            tools.append(toolResult("tf-async-" + i, "call_async_" + i, "goto",
                    "blocked: stopped 38 blocks short", Boolean.FALSE, "server-async", "failed")).append('\n');
        }
        Files.writeString(tmp.resolve("monitor/tools.jsonl"), tools.toString(), StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("monitor/state.jsonl"), state(), StandardCharsets.UTF_8);
        MemoQueue queue = new MemoQueue(tmp.resolve("queue.json"));

        assertEquals(1, ToolFailureIntake.scanOnce(tmp, id -> queue),
                "异步任务终态失败是显式判定，不能被三态收紧误杀");
    }

    @Test
    void serverSyncTerminalFailureCountsOnceEvenWithLongResult() throws Exception {
        // server-sync 的 result 可能被 2000 字截断；success 由写者按任务终态显式给出，仍要算失败。
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            tools.append(toolResult("tf-sync-" + i, "call_sync_" + i, "locate_structure",
                    "{\"success\":false,\"message\":\"not found\",\"data\":\"\"",
                    Boolean.FALSE, "server-sync")).append('\n');
        }
        Files.writeString(tmp.resolve("monitor/tools.jsonl"), tools.toString(), StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("monitor/state.jsonl"), state(), StandardCharsets.UTF_8);
        MemoQueue queue = new MemoQueue(tmp.resolve("queue.json"));

        assertEquals(1, ToolFailureIntake.scanOnce(tmp, id -> queue),
                "写者显式给出的终态失败不能被截断的 JSON 掩掉");
    }

    @Test
    void clientLocalExplicitFalseWithoutResultBodyStillCounts() throws Exception {
        // LocalToolCallLog.publishResult 只写 success 布尔、不写 result 正文；
        // 真实失败不能因为「读不到正文」被丢掉（2026-11-01 复核 D2）。
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            tools.append(toolResult("tf-local-" + i, "call_local_" + i, "load_skill",
                    "", Boolean.FALSE, "client-local")).append('\n');
        }
        Files.writeString(tmp.resolve("monitor/tools.jsonl"), tools.toString(), StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("monitor/state.jsonl"), state(), StandardCharsets.UTF_8);
        MemoQueue queue = new MemoQueue(tmp.resolve("queue.json"));

        assertEquals(1, ToolFailureIntake.scanOnce(tmp, id -> queue),
                "本地工具显式 false（无 result 正文）必须仍算失败");
    }

    @Test
    void replayedFailureAfterReviewIsNotCountedAgain() throws Exception {
        // 2026-11-02 回归：换代重扫会把「已复核移除」的同一事件重新入队。
        // seen 命中时 append 返回 true，但 contains=false —— 不得计 memos、不得写 sample。
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            tools.append(toolResult("tf-replay-" + i, "call_replay_" + i, "goto",
                    "{\"success\":false,\"message\":\"blocked: stopped 2 blocks short\"}")).append('\n');
        }
        Files.writeString(tmp.resolve("monitor/tools.jsonl"), tools.toString(), StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("monitor/state.jsonl"), state(), StandardCharsets.UTF_8);
        MemoQueue queue = new MemoQueue(tmp.resolve("queue.json"));

        assertEquals(1, ToolFailureIntake.scanOnce(tmp, id -> queue), "首扫：3 连败成 1 条");
        List<Memo> taken = queue.drain(10);
        assertEquals(1, queue.commitDrain(taken), "模拟复核消费掉这条");
        assertEquals(0, queue.size());

        Files.deleteIfExists(tmp.resolve("tool-failure-cursor.json"));
        ToolFailureIntake.resetForTest();
        assertEquals(0, ToolFailureIntake.scanOnce(tmp, id -> queue),
                "同一事件重放：已入过队的不再计入（memos 不能 +1）");
        assertEquals(0, queue.size(), "也不得回灌");
    }

    @Test
    void stoppedAsyncTaskIsNotAFailure() throws Exception {
        // 2026-11-01 实机复核 P1：stopped = 主动取消/被顶替，不是工具失败（与 harness action-ledger 同口径）。
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            tools.append(toolResult("tf-stop-" + i, "call_stop_" + i, "goto",
                    "cancelled before reaching target", Boolean.FALSE, "server-async", "stopped")).append('\n');
        }
        Files.writeString(tmp.resolve("monitor/tools.jsonl"), tools.toString(), StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("monitor/state.jsonl"), state(), StandardCharsets.UTF_8);
        MemoQueue queue = new MemoQueue(tmp.resolve("queue.json"));

        assertEquals(0, ToolFailureIntake.scanOnce(tmp, id -> queue),
                "主动取消的任务不是失败，不得生成复盘单元");
        assertEquals(0, queue.size());
    }

    @Test
    void stoppedWithBlockedTextIsSkippedKnownLoss() throws Exception {
        // 已知漏判（L2）：blocked-stopped（被挡在 38 格之外）其实是真失败，但当前按
        // async_status=stopped 整类跳过。本用例钉住现状 + 标记损失，不许当成「stopped 都安全」。
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            tools.append(toolResult("tf-blocked-" + i, "call_blocked_" + i, "goto",
                    "blocked: stopped 38.2 blocks short horizontally and 17 blocks above you",
                    Boolean.FALSE, "server-async", "stopped")).append('\n');
        }
        Files.writeString(tmp.resolve("monitor/tools.jsonl"), tools.toString(), StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("monitor/state.jsonl"), state(), StandardCharsets.UTF_8);
        MemoQueue queue = new MemoQueue(tmp.resolve("queue.json"));

        assertEquals(0, ToolFailureIntake.scanOnce(tmp, id -> queue),
                "当前实现会跳过 stopped；这是已知损失，修法见 L2 reason-code（不得用文本正则兜底）");
    }

    @Test
    void duplicateWritersCountOneCallAndRealFailuresStillCount() throws Exception {
        // 同一次真实失败被 server + client 各记一行；阈值按「调用次数」算，不按「日志行数」算。
        StringBuilder tools = new StringBuilder();
        for (int i = 0; i < 2; i++) {
            String res = "{\"success\":false,\"message\":\"blocked: stopped 38 blocks short\"}";
            tools.append(toolResult("tf-dup-a" + i, "call_dup_" + i, "goto", res, null, null)).append('\n');
            tools.append(toolResult("tf-dup-b" + i, "call_dup_" + i, "goto", res, Boolean.FALSE, "client")).append('\n');
        }
        Files.writeString(tmp.resolve("monitor/tools.jsonl"), tools.toString(), StandardCharsets.UTF_8);
        Files.writeString(tmp.resolve("monitor/state.jsonl"), state(), StandardCharsets.UTF_8);
        MemoQueue queue = new MemoQueue(tmp.resolve("queue.json"));

        assertEquals(0, ToolFailureIntake.scanOnce(tmp, id -> queue),
                "2 次真实失败 × 2 个 writer = 4 行，仍不足以凑成 3 连败");
        assertEquals(0, queue.size());

        // 第 3 次真实失败（仍带重复写者）才形成复盘单元，且只有一条。
        StringBuilder more = new StringBuilder(tools.toString());
        String res = "{\"success\":false,\"message\":\"blocked: stopped 38 blocks short\"}";
        more.append(toolResult("tf-dup-a3", "call_dup_3", "goto", res, null, null)).append('\n');
        more.append(toolResult("tf-dup-b3", "call_dup_3", "goto", res, Boolean.FALSE, "client")).append('\n');
        Files.writeString(tmp.resolve("monitor/tools.jsonl"), more.toString(), StandardCharsets.UTF_8);

        assertEquals(1, ToolFailureIntake.scanOnce(tmp, id -> queue), "第 3 次真实失败必须形成一条复盘单元");
        assertEquals(1, queue.size());
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
