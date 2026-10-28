package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code rdd_report_yield}：现场数量回报（2026-10-28，主人第一点）。
 *
 * <h2>为什么需要它</h2>
 * 主人原话：「我进村庄里面本来是要拿干草块，但是我在规划的时候不知道村庄有多少干草块，
 * 那我就加一个最低数字……到了这个地方了之后再做决定……这个数量是依据执行 AI 汇报，
 * 或者说到了那个地方再扫一遍」。
 *
 * <p>规划侧能做的只有"先给一个小值起步"（拆步提示里已经写了 1~3）。但硬判据一旦是 3，
 * 她拿了 3 个就算这一步完了 —— 「其实这里有 12 个，值不值得都拿了」这件事链永远不知道。
 * 本工具就是那个回报口：把现场实际数报上来，并把这一步的目标<b>往上抬</b>。
 *
 * <h2>为什么只许往上抬</h2>
 * 见 {@code TaskChain.raiseCurrentMinimum}：下调会让硬判据当场变真（假完成）。
 * 所以这里收到比当前目标更小的数时，只记录、不改判据，并明确回执"目标未变"。
 */
final class RddReportYieldTool implements NumenTool {

    @Override
    public String name() {
        return "rdd_report_yield";
    }

    @Override
    public String description() {
        return "Report what you actually found on site for the CURRENT step, and raise this step's target if there is more "
                + "than the plan assumed (e.g. planned 3 hay bales, the village actually has 12). "
                + "The target can only be raised, never lowered — lowering would instantly fake-complete the step. "
                + "Report `observed` (how much exists here) and `take` (how much you will actually collect; defaults to observed). "
                + "If take <= current target the call still succeeds but the target stays unchanged. "
                + "Read rdd_status first for expected_subtask_id.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("expected_subtask_id", "Current subtask ID from rdd_status.")
                .string("asset_key", "The item you counted (real namespaced id), for the record.")
                .integer("observed", "How many of this item you actually see here.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .integer("take", "How many you will collect; defaults to observed.", Integer.MIN_VALUE, Integer.MAX_VALUE)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            RddRuntime rt = RddPlugin.runtime(companion.getUUID());
            if (rt == null) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail("no active RDD task chain").toJson());
                return;
            }
            TaskChain chain = rt.chain();
            Subtask current = chain.currentSubtask();
            if (current == null) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "no executable subtask right now (the stage may still be expanding)").toJson());
                return;
            }
            String expected = str(args, "expected_subtask_id");
            if (expected.isBlank() || !expected.equals(current.id())) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "yield refused: stale subtask id (current=" + current.id() + ")").toJson());
                return;
            }
            int observed = intOf(args, "observed", -1);
            int take = intOf(args, "take", observed);
            String assetKey = str(args, "asset_key");
            if (observed < 0) {
                throw new IllegalArgumentException("observed must be >= 0");
            }
            int before = chain.currentMinimum(current.id());
            boolean raised = false;
            int applied = before;
            if (take > before) {
                int old = chain.raiseCurrentMinimum(current.id(), take);
                raised = true;
                applied = take;
                RddMonitor.publish("yield_target_raised", data(companion, current.id(), assetKey, observed, old, take));
                RddPlugin.saveRuntimes();
                RddPlugin.publishTaskSnapshot(companion.getUUID(), "yield_target_raised");
            } else {
                RddMonitor.publish("yield_reported", data(companion, current.id(), assetKey, observed, before, before));
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("subtask", current.id());
            out.put("target_before", before);
            out.put("target_now", applied);
            out.put("observed", observed);
            out.put("changed", raised);
            reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                    raised ? "target raised " + before + " → " + take + " (this step now needs " + take + ")"
                            : "yield recorded; target stays " + before + " (only upward changes are allowed)",
                    out).toJson());
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("yield report failed: " + ex.getMessage()).toJson());
        }
    }

    private static Map<String, Object> data(NumenPlayer companion, String subtask, String assetKey,
                                            int observed, int before, int after) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("companionId", companion.getUUID().toString());
        d.put("subtask", subtask);
        d.put("assetKey", assetKey == null ? "" : assetKey);
        d.put("observed", observed);
        d.put("targetBefore", before);
        d.put("targetAfter", after);
        return d;
    }

    private static String str(JsonObject args, String key) {
        return args != null && args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : "";
    }

    private static int intOf(JsonObject args, String key, int fallback) {
        if (args == null || !args.has(key) || args.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return args.get(key).getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
