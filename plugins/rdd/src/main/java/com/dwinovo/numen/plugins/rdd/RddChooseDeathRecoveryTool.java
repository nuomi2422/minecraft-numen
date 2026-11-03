package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 有限死亡恢复策略入口。模型只能选择预注册策略，不能创建流程、删任务或改事实。
 */
final class RddChooseDeathRecoveryTool implements NumenTool {


    private static final String[] DECISIONS = {
            "RECOVER_DROPS_FIRST",
            "FETCH_BACKUP_THEN_CONTINUE",
            "ABANDON_RECOVERY",
            "PREPARE_AND_REPLAN",
            "RESET_AND_RESTART"
    };

    @Override
    public String name() {
        return "rdd_choose_death_recovery";
    }

    @Override
    public String description() {
        return "Choose one protected death-recovery strategy only: RECOVER_DROPS_FIRST, "
                + "FETCH_BACKUP_THEN_CONTINUE, ABANDON_RECOVERY, PREPARE_AND_REPLAN, or RESET_AND_RESTART. "
                + "This changes only the active death_recovery side task decision; it cannot delete tasks, "
                + "change facts, confirm loss, or rewrite the mainline (RESET_AND_RESTART only freezes the "
                + "side task until rdd_restart_mainline is called). Unknown strategies are rejected.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("decision", "One of the four fixed death recovery strategies")
                .string("reason", "Short evidence-based reason for the choice")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        if (companion == null || args == null || !args.has("decision")) {
            reply.accept(TaskResult.fail("active death_recovery and decision are required").toJson());
            return;
        }
        String raw = args.get("decision").getAsString();
        String reason = args.has("reason") ? args.get("reason").getAsString() : "";
        var parsed = com.dwinovo.numen.rdd.side.death.DeathRecoveryDecision.parse(raw);
        if (parsed == null) {
            reply.accept(TaskResult.fail("REJECTED_INVALID_DECISION: allowed=" + String.join(",", DECISIONS)).toJson());
            return;
        }
        if (reason.isBlank()) {
            reply.accept(TaskResult.fail("reason is required for a death recovery decision").toJson());
            return;
        }
        // 2026-11-02（death49–56 实机）：空手回死亡点捡装备 = 送死。
        // 上次死因是 lava/onFire/fall 且背包一件不剩时，RECOVER_DROPS_FIRST 直接拒绝，
        // 逼它先 FETCH_BACKUP_THEN_CONTINUE / PREPARE_AND_REPLAN（拿备用或先补装）。
        if (parsed == com.dwinovo.numen.rdd.side.death.DeathRecoveryDecision.RECOVER_DROPS_FIRST) {
            // T6：同点连死不再允许回死亡点，先撤离/换路线。
            // 2026-11-03 审核：判断收敛到 RddDeathLedger（RL-16 的 180s 窗口 + 8 格半径常量），
            // 不再本地扫 list、也不再用硬编的 24 与无窗口口径。
            if (RddDeathRecoveryGuard.repeatedNearby(companion.getUUID(),
                    RddPlugin.REPEAT_DEATH_WINDOW_TICKS, RddPlugin.REPEAT_DEATH_RADIUS_BLOCKS)) {
                reply.accept(TaskResult.fail("REFUSED_REPEATED_DEATH_AREA: 最近死亡集中在同一片 "
                        + (int) RddPlugin.REPEAT_DEATH_RADIUS_BLOCKS + " 格内（"
                        + (RddPlugin.REPEAT_DEATH_WINDOW_TICKS / 20) + " 秒窗口），回死亡点会继续送死；"
                        + "先撤离/换路线，或 FETCH_BACKUP_THEN_CONTINUE / PREPARE_AND_REPLAN。").toJson());
                return;
            }
            String lastCause = RddDeathRecoveryGuard.lastDeathCause(companion.getUUID());
            // 2026-11-02（death59 实机）：它捡回 7 件掉落（不算「空手」）但依然无甲，
            // 于是「空手守卫」放行、它又把自己送回下界死亡点。判据改成「无战斗装备」：
            // 背包空 或 胸甲槽空 都算没有保命装备。
            boolean noCombatGear = companion.getInventory().isEmpty()
                    || companion.getInventory().getArmor(2).isEmpty();
            String guardReason = RddDeathRecoveryGuard.recoverGuardReason(noCombatGear, lastCause);
            RddMonitor.publish("t6_recover_guard", Map.of(
                    "companionId", companion.getUUID().toString(),
                    "decision", parsed.name(), "reason", guardReason,
                    "lastCause", lastCause == null ? "" : lastCause));
            if (!RddDeathRecoveryGuard.allowRecoverFirst(noCombatGear, lastCause)) {
                reply.accept(TaskResult.fail("REFUSED_EMPTY_HANDS: 背包空手且上次死因是 " + lastCause
                        + "，回死亡点捡装备等于再死一次；先 FETCH_BACKUP_THEN_CONTINUE（拿备用）"
                        + "或 PREPARE_AND_REPLAN（先补装）。").toJson());
                return;
            }
        }
        if (!SideTaskHost.chooseDeathRecovery(companion.getUUID(), parsed.name(), reason)) {
            reply.accept(TaskResult.fail("no active death_recovery side task or decision was stale").toJson());
            return;
        }
        reply.accept(TaskResult.ok("death recovery strategy accepted: " + parsed.name(),
                Map.of("decision", parsed.name(), "protected", true)).toJson());
    }
}
