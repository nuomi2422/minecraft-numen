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
                .bool("verified", "If true, require every condition of the target stage to match server world facts before settling.")
                .bool("verify_final_goal", "If true (with verified), also require the final primary stage conditions to match.")
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
            boolean verified = args.has("verified") && args.get("verified").getAsBoolean();
            boolean verifyFinalGoal = args.has("verify_final_goal") && args.get("verify_final_goal").getAsBoolean();
            String flagError = verifyFlagError(args);
            if (!flagError.isEmpty()) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(flagError).toJson());
                return;
            }
            if (verified) {
                PrimaryGoal target = null;
                for (PrimaryGoal p : chain.goal().primaryGoals()) if (p.id().equals(targetId)) target = p;
                Map<String, Integer> counts = RddDetector.countInventory(companion);
                List<String> unmet = unmetConditions(target, chain.goal(), verifyFinalGoal,
                        sub -> RddDetector.conditionMatches(companion, sub, counts));
                if (target == null || !unmet.isEmpty()) {
                    java.util.Map<String, Object> rej = new java.util.LinkedHashMap<>();
                    rej.put("companionId", companion.getUUID().toString());
                    rej.put("primary", targetId);
                    rej.put("verified", true);
                    rej.put("verify_final_goal", args.has("verify_final_goal") && args.get("verify_final_goal").getAsBoolean());
                    rej.put("unmet", unmet);
                    RddMonitor.publish("primary_settle_rejected", rej);
                    reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                            "settle refused: verified=true but world facts do not satisfy stage '" + targetId
                                    + "' unmet=" + unmet).toJson());
                    return;
                }
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
            data.put("verified", verified);
            data.put("verify_final_goal", verifyFinalGoal);
            data.put("unmet", java.util.List.of());
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

    /** Selection only: target subtasks, plus final-primary subtasks when requested; predicate decides match. */
    static List<String> unmetConditions(PrimaryGoal target, com.dwinovo.numen.rdd.api.Goal goal,
                                        boolean verifyFinalGoal,
                                        java.util.function.Predicate<com.dwinovo.numen.rdd.api.Subtask> matches) {
        List<String> unmet = new ArrayList<>();
        if (target == null) return List.of("no_target");
        if (target.subtasks().isEmpty()) unmet.add("no_conditions");
        for (var sub : target.subtasks()) {
            if (!matchesSafely(matches, sub)) unmet.add(sub.id());
        }
        if (verifyFinalGoal) {
            if (goal == null || goal.primaryGoals().isEmpty()) {
                unmet.add("no_final_goal");
            } else {
                var goals = goal.primaryGoals();
                var finalGoal = goals.get(goals.size() - 1);
                // target == final is already checked above; do not add duplicate final: ids.
                if (!finalGoal.id().equals(target.id())) {
                    for (var sub : finalGoal.subtasks()) {
                        if (!matchesSafely(matches, sub)) unmet.add("final:" + sub.id());
                    }
                }
            }
        }
        return unmet;
    }

    private static boolean matchesSafely(java.util.function.Predicate<com.dwinovo.numen.rdd.api.Subtask> matches,
                                         com.dwinovo.numen.rdd.api.Subtask sub) {
        try {
            return matches != null && matches.test(sub);
        } catch (RuntimeException e) {
            return false;   // unknown/error is not evidence; tool will report unmet and not settle
        }
    }

    /** Parse the real tool args; missing fields keep old defaults (both false). */
    static String verifyFlagError(JsonObject args) {
        boolean verified = args != null && args.has("verified") && !args.get("verified").isJsonNull()
                && args.get("verified").getAsBoolean();
        boolean verifyFinalGoal = args != null && args.has("verify_final_goal")
                && !args.get("verify_final_goal").isJsonNull() && args.get("verify_final_goal").getAsBoolean();
        return verifyFlagError(verified, verifyFinalGoal);
    }

    /** Shared by tool and fixture tests: verify_final_goal is meaningless without verified facts. */
    static String verifyFlagError(boolean verified, boolean verifyFinalGoal) {
        return verifyFinalGoal && !verified
                ? "settle refused: verify_final_goal requires verified=true; no facts were checked" : "";
    }

    private static String str(JsonObject args, String key) {
        return args != null && args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : "";
    }
}
