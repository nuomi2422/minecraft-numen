package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code rdd_supervisor_submit} —— 从代码层面直接触发 Supervisor（Stage-A 规划），不经聊天框。
 * <h2>为什么要有它（2026-10-27 主人指示）</h2>
 * 主人原话的意思：调查游戏内 {@code /goal} 指令触发 supervisor 的路径，然后
 * 「写一个工具，让你（外部脚本/实验台）可以直接从代码层面发送指令并注册」。
 *
 * <p>现状是 {@code /goal} 只有一条真路：聊天框 → {@code GoalSinks} → {@code beginPlanning}。
 * 外部想触发它只有两条**歪**路：手打聊天（人肉）、或 {@code enqueue} 冒充主人消息（语义不对：
 * 那是「主人说话」的通道，还要求同伴在线）。而实验台要跑「超长线任务」（如通关 MC），
 * 需要一条**可脚本化、可注册、留事件账**的直路。
 *
 * <h2>设计（刻意贴着既有真路，不造第二条规划通道）</h2>
 * <ul>
 *   <li>入口与 {@code GoalSinks} 的 goal sink **同一段代码**（{@code RddPlugin.submitSupervisorGoal}），
 *       走 {@code beginPlanning} → Stage-A → 绑链，**不旁路任何规划语义**；</li>
 *   <li>事件 {@code supervisor_input} 带 {@code source=rdd_supervisor_submit}（既有 sink 是
 *       {@code goal_sink}），监测台能分清「这条链是谁发起的」；</li>
 *   <li>{@code register=true}（缺省）时把目标同时写进引擎 {@code GoalState}（面板/轮末简报能看到），
 *       {@code false} 时只走 RDD 链不写引擎目标——给实验台留「只测链、不污染目标面板」的口；</li>
 *   <li>同伴不在线 → 显式拒绝（Stage-A 的 llmAsk 与后续绑定都要求活跃身体，不静默丢）。</li>
 * </ul>
 */
public final class RddSupervisorSubmitTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Input(String objective, Boolean register) {}

    @Override
    public String name() {
        return "rdd_supervisor_submit";
    }

    @Override
    public String description() {
        return "Submit a long-horizon objective straight to the RDD supervisor (Stage-A planner) "
                + "from code — no chat box, no impersonating the owner. Use for scripted long runs "
                + "(e.g. '通关MC'). Same planning path as /goal; the companion must be online. "
                + "register=false skips writing the engine GoalState (chain-only, for experiments).";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("objective", "The long-horizon objective text, e.g. 通关MC. Required, non-blank.")
                .optionalBool("register", "Also write the objective into the engine GoalState so the "
                        + "panel/turn-end briefing can see it (default true).")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Input input = GSON.fromJson(args, Input.class);
            String objective = input == null || input.objective() == null ? "" : input.objective().trim();
            if (objective.isBlank()) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "rdd_supervisor_submit requires a non-blank objective").toJson());
                return;
            }
            if (objective.length() > com.dwinovo.numen.rdd.core.RddChainFactory.MAX_OBJECTIVE_CHARS) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "objective too long (max " + com.dwinovo.numen.rdd.core.RddChainFactory.MAX_OBJECTIVE_CHARS
                                + " chars, aligned with GoalState.MAX_OBJECTIVE_CHARS)").toJson());
                return;
            }
            boolean register = input.register() == null || input.register();
            String result = RddPlugin.submitSupervisorGoal(
                    companion.getUUID(), objective, register, "rdd_supervisor_submit");
            reply.accept(com.dwinovo.numen.task.TaskResult.ok(result, Map.of(
                    "objective", objective, "register", register)).toJson());
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                    "rdd_supervisor_submit failed: " + ex).toJson());
        }
    }
}
