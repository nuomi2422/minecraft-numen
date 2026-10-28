package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code rdd_settle_stage}：把某一级记成「事实上已经达成 / 不再需要」（2026-10-28，主人定调）。
 *
 * <h2>主人原话</h2>
 * 「提前收口这种东西是规划者决定的……我拿到了钻石镐，挖铁镐就没有意义了……
 * 当然也有：我有钻石镐了，但是我没有铁装备，那么我可以规划一下，用钻石镐来挖铁，
 * 而不是继续去挖到石镐之后再来挖铁」。
 *
 * <p>所以这是一个<b>规划者按钮</b>，不是自动规则：判断"这一级还有没有意义"的是它，
 * 代码只保证三件事 —— ① 收口必须写理由；② 收口=记阶段事实（跨重绑可继承），
 * 不是删节点；③ 当前级收口立刻推进、后面的级只记事实并在走到时跳过。
 *
 * <h2>什么时候用</h2>
 * <ul>
 *   <li>目标已经用别的方式达成（"最终就是要钻石镐，我已经有了"）；</li>
 *   <li>某一级的目的被现有能力覆盖（有钻石镐 → 石镐/铁镐那一级不必走）；</li>
 *   <li>某一级的前提已经不成立（它要的东西已经被更好的东西替代）。</li>
 * </ul>
 * <p>只是"想换个做法但这一级仍要做" → 用 {@code rdd_patch} 或 {@code rdd_replan_chain}，不要收口。
 */
final class RddSettleStageTool implements NumenTool {

    @Override
    public String name() {
        return "rdd_settle_stage";
    }

    @Override
    public String description() {
        return "Mark a stage (the current one or a later one) as ALREADY ACHIEVED / NO LONGER NEEDED — the planner's own "
                + "judgement, not an automatic rule. Use it when the stage's purpose is already covered by what you have "
                + "(diamond pickaxe can mine obsidian, so a stone/iron pickaxe stage no longer makes sense), or when the "
                + "final goal is effectively met (\"the goal was to get a diamond pickaxe — I have it\"). "
                + "A reason is REQUIRED (it is the only thing that can be reviewed later). "
                + "Effects: the stage key is recorded as a completion fact (inherited by future chains re-submitting the same "
                + "stage), its unfinished steps become SKIPPED with your reason, and the cursor advances immediately for the "
                + "current stage. Completed steps are never downgraded. Use rdd_patch / rdd_replan_chain when the stage still "
                + "has to happen but with a different approach.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("primary_id", "Stage id to settle. Use \"current\" (or omit) for the stage you are on now.")
                .string("reason", "What makes this stage unnecessary or already achieved (required, reviewable).")
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
            String reason = str(args, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason required: say what makes this stage unnecessary");
            }
            String requested = str(args, "primary_id").trim();
            String targetId = (requested.isEmpty() || "current".equalsIgnoreCase(requested))
                    ? chain.currentPrimary().id()
                    : requested;
            List<String> known = new ArrayList<>();
            for (PrimaryGoal p : chain.goal().primaryGoals()) {
                known.add(p.id());
            }
            if (!known.contains(targetId)) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "settle refused: unknown stage '" + targetId + "'; known stages: " + known).toJson());
                return;
            }
            long before = chain.stateVersion();
            String desc = chain.settlePrimaryByFact(targetId, reason);
            // 跨链继承：写进完成事实库，重提同一目标时同一阶段自动跳过（与"跑完了"同一待遇）。
            RddPlugin.recordStageFact(companion.getUUID(), chain.goal(), desc);
            RddPlugin.saveRuntimes();
            RddPlugin.publishTaskSnapshot(companion.getUUID(), "stage_settled_by_fact");

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("companionId", companion.getUUID().toString());
            data.put("primary", targetId);
            data.put("description", desc);
            data.put("reason", reason);
            data.put("stateVersionBefore", before);
            data.put("stateVersionAfter", chain.stateVersion());
            data.put("primaryStatus", chain.primaryStatus().name());
            data.put("currentPrimary", chain.currentPrimary().id());
            RddMonitor.publish("primary_settled_by_fact", data);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("settled", targetId);
            out.put("description", desc);
            out.put("current_primary", chain.currentPrimary().id());
            out.put("primary_status", chain.primaryStatus().name());
            reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                    "stage settled by fact: " + desc + " → now at " + chain.currentPrimary().id()
                            + " (" + chain.primaryStatus() + ")", out).toJson());
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("settle failed: " + ex.getMessage()).toJson());
        }
    }

    private static String str(JsonObject args, String key) {
        return args != null && args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : "";
    }
}
