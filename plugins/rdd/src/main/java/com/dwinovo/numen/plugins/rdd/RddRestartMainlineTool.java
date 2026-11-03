package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code rdd_restart_mainline} —— 169 T6 / 170 §5.1：死亡或装备全丢后，把旧主线作废、按新目标重新规划。
 *
 * <p>为什么需要它：死亡只走「恢复支线」，恢复支线按契约只能改策略、不能重写主线；
 * 于是「甲/镐/食物全丢、人在下界」时，链仍指向「再进下界取烈焰棒」，恢复完又回去送死。
 * 本工具是唯一的主线改单入口：走与 /goal 相同的 Stage-A 规划真路，绑链后清掉历史完成继承。
 *
 * <p>边界：要求身体空闲（正在跑的动作不能被半路换目标）；reason 必填；objective 必须是新的下一步真实目标，
 * 不能用来跳过单个步骤或绕过正常判定。
 */
final class RddRestartMainlineTool implements NumenTool {

    @Override
    public String name() {
        return "rdd_restart_mainline";
    }

    @Override
    public String description() {
        return "Restart the RDD mainline from scratch with a NEW objective. Use after death or lost gear when the "
                + "old objective is no longer safe (e.g. 'go back to the nether for blaze rods' while carrying no "
                + "armor), or when the current chain keeps pointing at a dead plan. It discards the old chain, "
                + "clears inherited completed stages (they must be re-earned), and re-runs Stage-A planning for the "
                + "new objective. Requires an idle body and a concrete observed reason. Do NOT use it for normal "
                + "progress, to skip one step, or to restate the old objective; the new objective must be the next "
                + "real goal (e.g. '先补铁甲与食物，再回主世界基地').";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("objective", "The NEW long-horizon objective she should pursue now.")
                .string("reason", "Observed reason for restarting (death, lost gear, route unsafe). Required.")
                .optionalString("expected_goal_id", "Optional safety check: current goal id from rdd_status; "
                        + "refuse if the chain changed since it was read.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            if (companion == null || args == null) {
                reply.accept(TaskResult.fail("active companion is required").toJson());
                return;
            }
            String objective = args.has("objective") ? args.get("objective").getAsString().trim() : "";
            String reason = args.has("reason") ? args.get("reason").getAsString().trim() : "";
            if (objective.isBlank()) {
                reply.accept(TaskResult.fail("objective is required for a mainline restart").toJson());
                return;
            }
            if (reason.isBlank()) {
                reply.accept(TaskResult.fail("reason is required for a mainline restart").toJson());
                return;
            }
            // 身体正在跑动作时换目标 = 旧动作没有终态、新任务又没有执行槽；先等它停。
            if (CompanionTickDispatcher.currentTaskFor(companion.getUUID()) != null) {
                reply.accept(TaskResult.fail(
                        "refused: body is busy; wait for the current action to stop before restarting the mainline").toJson());
                return;
            }
            if (args.has("expected_goal_id") && !args.get("expected_goal_id").getAsString().isBlank()) {
                String expected = args.get("expected_goal_id").getAsString().trim();
                var rt = RddPlugin.runtime(companion.getUUID());
                String current = rt == null ? "" : rt.chain().goal().id();
                if (!expected.equals(current)) {
                    reply.accept(TaskResult.fail(
                            "refused: stale expected_goal_id; read rdd_status again (current=" + current + ")").toJson());
                    return;
                }
            }
            String result = RddPlugin.restartMainline(companion.getUUID(), objective, reason);
            boolean accepted = result.startsWith("mainline restart accepted");
            reply.accept(accepted
                    ? TaskResult.ok(result, Map.of("objective", objective, "plannedFromScratch", true)).toJson()
                    : TaskResult.fail(result).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("mainline restart failed: " + ex).toJson());
        }
    }
}
