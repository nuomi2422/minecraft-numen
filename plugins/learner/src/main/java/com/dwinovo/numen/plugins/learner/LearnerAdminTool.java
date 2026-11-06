package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 外部专用管理工具（2026-11-06）：撤回技能。
 *
 * <p>**不进执行者（内脑）清单**（{@link #externalOnly()}），只从外部/MCP 或主人侧调用——
 * 否则同伴可以自己把学到的经验撤掉。语义见 {@link com.dwinovo.numen.plugins.learner.skills.SkillRegistry#retire}：
 * 追加同 id 新版本 status=REVOKED、supersedes 指旧版；事件 learner_retired 可追溯。
 */
final class LearnerAdminTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String action, String skillId, String reason, Boolean confirm) {}

    @Override public String name() { return "learner_admin"; }

    @Override public boolean externalOnly() { return true; }

    @Override public String description() {
        return "External-only admin for the skill library (owner side). action=retire|promote, skillId, reason, "
                + "confirm=true. retire writes status=REVOKED, promote writes status=TRIAL (only TRIAL/PUBLISHED "
                + "are retrievable). Both append a new version with supersedes and keep history.";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("action", "'retire' (hide it) or 'promote' (make it retrievable: DRAFT/PUBLISHED -> TRIAL).")
                .string("skillId", "Skill id to retire, e.g. sk-xxxx.")
                .string("reason", "Why it is retired (kept in learner_retired event).")
                .optionalBoolean("confirm", "Must be true; without it the call is rejected.")
                .build();
    }

    static String validate(String action, String skillId, String reason, Boolean confirm) {
        if (!"retire".equals(action) && !"promote".equals(action)) {
            return "learner_admin: action must be 'retire' or 'promote'";
        }
        if (skillId == null || skillId.isBlank()) return "learner_admin: skillId is required";
        if (reason == null || reason.isBlank()) return "learner_admin: reason is required";
        if (!Boolean.TRUE.equals(confirm)) return "learner_admin: confirm=true is required";
        return null;
    }

    @Override public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args parsed = GSON.fromJson(args, Args.class);
        String error = validate(parsed == null ? null : parsed.action(), parsed == null ? null : parsed.skillId(),
                parsed == null ? null : parsed.reason(), parsed == null ? null : parsed.confirm());
        if (error != null) {
            reply.accept(TaskResult.fail(error).toJson());
            return;
        }
        Map<String, Object> result = "promote".equals(parsed.action())
                ? LearnerPlugin.promoteSkill(parsed.skillId(), parsed.reason(), "external")
                : LearnerPlugin.retireSkill(parsed.skillId(), parsed.reason(), "external");
        if (result.containsKey("error")) {
            reply.accept(TaskResult.fail("learner_admin: " + result.get("error")).toJson());
            return;
        }
        String verb = "promote".equals(parsed.action()) ? "promoted" : "retired";
        reply.accept(TaskResult.ok(verb + " " + parsed.skillId() + " -> " + result.get("outcome")
                + " v" + result.get("newVersion"), result).toJson());
    }
}
