package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.experience.api.PublicationState;
import com.dwinovo.numen.experience.core.ExperienceMemory;
import com.dwinovo.numen.experience.core.ExperienceStore;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * G6a：把一条经验在发布资格状态机里迁移（DRAFT/TRIAL/PUBLISHED/SUSPENDED/REVOKED）。
 *
 * <p><b>为什么发布要单独一个工具</b>：成熟度（{@code experience_verify}）回答「这条经验有多可信」，
 * 发布资格回答「允不允许拿它指导行动」。一条被反复验证的经验，也可能因为主人叫停或环境变化被撤回；
 * 反过来，一条刚学到、还没验证的经验，也不该因为「看着像对的」就自动进执行视图。
 * 两件事混在一个工具里，就会出现「验证=放行」的暗通道。
 *
 * <p><b>迁移合法性只认 {@link PublicationState#canTransitionTo}</b>：非法迁移不改状态并如实回错，
 * 不做「尽力而为」的自动纠偏（那会让状态机变成摆设）。
 */
final class ExperiencePublishTool implements NumenTool {

    private static final Gson GSON = new Gson();

    @Override
    public String name() {
        return "experience_publish";
    }

    @Override
    public String description() {
        return "Move one experience through the publication state machine: DRAFT -> TRIAL -> PUBLISHED, "
                + "PUBLISHED -> SUSPENDED -> PUBLISHED, any -> REVOKED (terminal). Only PUBLISHED entries can "
                + "appear in the executor view (the context used while she is acting). This does NOT change the "
                + "entry body and does NOT mean 'verified' — verification is experience_verify. Illegal "
                + "transitions fail without changing the entry.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("id", "经验条目的稳定 id（experience_learn / experience_recall 里有）。")
                .enumStr("target", "目标发布状态", "DRAFT", "TRIAL", "PUBLISHED", "SUSPENDED", "REVOKED")
                .optionalString("note", "这次迁移的理由（会写进日志，便于审计）。")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Input in = GSON.fromJson(args, Input.class);
            if (in == null || in.id() == null || in.id().isBlank()) {
                reply.accept(TaskResult.fail("experience_publish requires id").toJson());
                return;
            }
            PublicationState target;
            try {
                target = PublicationState.valueOf(String.valueOf(in.target()).trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException ex) {
                reply.accept(TaskResult.fail("unknown target: " + in.target()
                        + " (DRAFT|TRIAL|PUBLISHED|SUSPENDED|REVOKED)").toJson());
                return;
            }
            ExperienceMemory memory = ExperiencePlugin.memory(companion.getUUID());
            ExperienceStore.PublishResult r = memory.publish(in.id().trim(), target, in.note());
            if (!r.ok()) {
                reply.accept(TaskResult.fail(r.code() + ": " + r.message()).toJson());
                return;
            }
            reply.accept(TaskResult.ok("publication updated: " + r.message(), Map.of(
                    "id", r.entry().id(),
                    "publication", r.entry().publication().name(),
                    "maturity", r.entry().maturity().name())).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("experience_publish failed: " + ex.getMessage()).toJson());
        }
    }

    private record Input(String id, String target, String note) {}
}
