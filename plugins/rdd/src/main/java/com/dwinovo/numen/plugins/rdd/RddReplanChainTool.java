package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code rdd_replan_chain}：重写"当前阶段之后的路"（2026-10-28，主人第二/第四点）。
 *
 * <h2>为什么需要它</h2>
 * 主人原话：「语义重规划，它是可以规划整条链路的，就是整个链重新写一遍就可以了……
 * 他知道因为是什么东西，拿来做什么的，所以他才好重新规划整条任务」。
 *
 * <p>此前重规划只能改"当前一级的二级清单"（{@code replaceCurrentSubtasks}）或
 * "当前一级里还没做的节点"（{@code rdd_patch}）。于是「我丢了整套钻石甲，
 * 后面的下界/末地阶段全都不成立了」这种处境，规划器看懂了也没有地方落笔。
 *
 * <h2>边界（刻意窄）</h2>
 * <ul>
 *   <li>只重写<b>当前一级之后</b>的一级；当前一级及其之前一字不动（完成的事实不能被抹掉）；</li>
 *   <li>新一级必须<b>未展开</b>：二级仍由懒展开生成（§9 懒加载纪律）；</li>
 *   <li>只在"后面的路整体不成立"时用；改一两步请用 {@code rdd_patch}；</li>
 *   <li>传 {@code []} = 明确砍掉后面所有阶段（"只差最后一件东西"）。</li>
 * </ul>
 *
 * <p>格式与 Stage-A 一致：{@code [{"theme":"...","wait_for":[{"asset_key":"minecraft:x","minimum":2}]}]}
 * —— 直接复用 {@link RddStagePlanner#parse} 的容错解析（脏键/负数量/超上限一律丢弃，不编造）。
 */
final class RddReplanChainTool implements NumenTool {

    @Override
    public String name() {
        return "rdd_replan_chain";
    }

    @Override
    public String description() {
        return "Rewrite the REMAINING stages of the chain (everything after the current stage), keeping the current stage "
                + "and all completed work untouched. Use it only when the rest of the road is no longer valid: you lost core "
                + "gear with no backup, the owner changed direction, or the remaining stages assumed something that is now false. "
                + "For one or two nodes use rdd_patch instead. "
                + "`stages` is a JSON array like [{\"theme\":\"先回主世界补齐装备\",\"wait_for\":[{\"asset_key\":\"minecraft:iron_pickaxe\",\"minimum\":1}]}] "
                + "— same shape as the planner's stage list; themes only (no subtasks). "
                + "An empty array [] means: drop every remaining stage (only the last item is left).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("stages", "JSON array of {theme, wait_for?} — the new remaining stages, in order. [] = none left.")
                .string("reason", "Why the remaining road is invalid (goes into the receipt and monitor event).")
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
            String raw = str(args, "stages").trim();
            String reason = str(args, "reason");
            if (raw.isBlank()) {
                throw new IllegalArgumentException("stages required (use [] to drop every remaining stage)");
            }
            boolean dropAll = "[]".equals(raw.replaceAll("\\s+", ""));
            List<PrimarySpec> specs = RddStagePlanner.parse("{\"stages\":" + raw + "}");
            if (specs.isEmpty() && !dropAll) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "replan refused: no usable stage (every theme was blank or the JSON did not parse)").toJson());
                return;
            }
            long version = chain.stateVersion();
            String suffix = companion.getUUID().toString().substring(0, 8);
            List<PrimaryGoal> goals = new ArrayList<>(specs.size());
            for (int i = 0; i < specs.size(); i++) {
                PrimarySpec spec = specs.get(i);
                // id 带"写入时的 stateVersion"：同一版本内不会撞，重写两次也天然分开
                // （旧 id 一律留在历史里，迟到的回执匹配不上新阶段）。
                goals.add(PrimaryGoal.unexpanded(
                        "primary-" + suffix + "-v" + (version + 1) + "-" + i,
                        spec.description(), spec.waitFor()));
            }
            int count = chain.replaceFuturePrimaries(goals);
            RddPlugin.saveRuntimes();
            RddPlugin.publishTaskSnapshot(companion.getUUID(), "future_plan_rewritten");
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("companionId", companion.getUUID().toString());
            data.put("stages", count);
            data.put("reason", reason == null ? "" : reason);
            data.put("stateVersion", version + 1);
            RddMonitor.publish("future_plan_rewritten", data);
            reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                    "remaining stages rewritten: " + count + " stage(s); the current stage is untouched",
                    Map.of("stages", count, "state_version", version + 1)).toJson());
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("chain replan failed: " + ex.getMessage()).toJson());
        }
    }

    private static String str(JsonObject args, String key) {
        return args != null && args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : "";
    }
}
