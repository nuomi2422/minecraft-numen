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
            "PREPARE_AND_REPLAN"
    };

    @Override
    public String name() {
        return "rdd_choose_death_recovery";
    }

    @Override
    public String description() {
        return "Choose one protected death-recovery strategy only: RECOVER_DROPS_FIRST, "
                + "FETCH_BACKUP_THEN_CONTINUE, ABANDON_RECOVERY, or PREPARE_AND_REPLAN. "
                + "This changes only the active death_recovery side task decision; it cannot delete tasks, "
                + "change facts, confirm loss, or rewrite the mainline. Unknown strategies are rejected.";
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
        if (!SideTaskHost.chooseDeathRecovery(companion.getUUID(), parsed.name(), reason)) {
            reply.accept(TaskResult.fail("no active death_recovery side task or decision was stale").toJson());
            return;
        }
        reply.accept(TaskResult.ok("death recovery strategy accepted: " + parsed.name(),
                Map.of("decision", parsed.name(), "protected", true)).toJson());
    }
}
