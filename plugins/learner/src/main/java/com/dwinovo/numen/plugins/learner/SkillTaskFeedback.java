package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.plugins.learner.core.UsageLedger;
import com.dwinovo.numen.plugins.learner.skills.SkillBodyStore;
import com.dwinovo.numen.plugins.learner.skills.SkillEntry;
import com.dwinovo.numen.plugins.learner.skills.SkillRegistrar;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 2026-11-12 046-F: explicit model choice during one running task, then original terminal facts.
 * No exposure/time-neighbor inference; a complete server reply is not client acknowledgment.
 * Result rows are review candidates with NONE evidence, never proof of effectiveness.
 */
final class SkillTaskFeedback {
    private SkillTaskFeedback() {}

    static void handle(String action, String callId, JsonObject args, Path dir, UsageLedger ledger,
                       LearnerUsageTool.TaskAccess tasks, Consumer<String> reply) {
        // Consume transport failures here: never retry the failed reply or bind after it throws.
        try {
            if (dir == null || tasks == null || tasks.companionId() == null) {
                throw new IllegalArgumentException("UNKNOWN: 缺少当前同伴/服务器/配置");
            }
            String taskId = required(args, "task_id");
            String ref = required(args, "skill_ref");
            String version = required(args, "version");
            if (!ref.matches("skill:[^\\s:@]+") || !version.matches("v[1-9][0-9]*")) {
                throw new IllegalArgumentException("需要规范 skill:<id> 与 vN 版本");
            }
            if (args.has("outcome") || args.has("success")) {
                throw new IllegalArgumentException("技能结果只取原任务终态，不能自报 outcome/success");
            }
            TaskRecord task = action.equals("ADOPT_SKILL") ? tasks.current() : tasks.finished(taskId);
            WorldContextStamp.Token token = tasks.identity();
            if (task == null || !taskId.equals(task.publicId())) {
                throw new IllegalArgumentException("UNKNOWN: 精确 task_id 无原任务记录（可能已驱逐）");
            }
            if (!TaskRecord.completeIdentity(token) || !token.equals(task.identityToken())) {
                throw new IllegalArgumentException("UNKNOWN: 原任务与当前 world/session/epoch 不一致");
            }
            if (task.getToolCallId() == null || task.getToolCallId().isBlank()) {
                throw new IllegalArgumentException("UNKNOWN: 原任务缺少 tool_call_id");
            }
            SkillEntry skill = SkillRegistrar.registryFor(dir).get(ref.substring("skill:".length())).orElse(null);
            if (skill == null || !version.equals("v" + skill.version()) || !skill.status().retrievable()) {
                throw new IllegalArgumentException("技能当前版本或可检索状态不匹配");
            }
            String body = SkillBodyStore.read(dir, skill.bodyRef());
            if (body == null || body.isBlank()) throw new IllegalArgumentException("技能正文缺失/损坏");
            String digest = digest(body);
            if (action.equals("ADOPT_SKILL")) {
                adopt(callId, task, ref, version, skill.bodyRef(), digest, body, tasks, token, reply);
            } else {
                report(task, ref, version, skill, digest, tasks, token, ledger, reply);
            }
        } catch (RuntimeException e) {
            send(reply, TaskResult.fail("learner_usage " + action.toLowerCase(java.util.Locale.ROOT)
                    + " 拒绝: " + e.getMessage()).toJson());
        }
    }

    private static void adopt(String callId, TaskRecord task, String ref, String version,
                              String bodyRef, String digest, String body, LearnerUsageTool.TaskAccess tasks,
                              WorldContextStamp.Token token, Consumer<String> reply) {
        TaskRecord.SkillSelection choice = new TaskRecord.SkillSelection(ref, version, bodyRef, digest,
                callId, tasks.companionId().toString(), token);
        if (!task.canBindSkillSelection(choice)) {
            throw new IllegalArgumentException("仅能绑定当前 RUNNING 任务；单任务换技能/换版冲突");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("task_id", task.publicId());
        data.put("skill_ref", ref);
        data.put("version", version);
        data.put("body_ref", bodyRef);
        data.put("body_digest", digest);
        data.put("body", body);
        data.put("meaning", "模型在运行中选择此版；服务端完整回复，不证明客户端收到、按正文执行或因果有效");
        String json = TaskResult.ok("技能完整正文；选择与原任务结果只作待审候选", data).toJson();
        if (json.length() > TaskResult.MAX_RESULT_JSON_LENGTH) {
            throw new IllegalArgumentException("完整回复超过正常工具报文上限；拒绝截断后绑定");
        }
        if (!send(reply, json)) return;
        // Normal calls execute on the server thread. Recheck for a terminal/context change during reply.
        if (tasks.current() == task && token.equals(tasks.identity())
                && choice.companionId().equals(tasks.companionId().toString())) {
            task.bindSkillSelection(choice);
        }
    }

    private static void report(TaskRecord task, String ref, String version, SkillEntry skill, String digest,
                               LearnerUsageTool.TaskAccess tasks, WorldContextStamp.Token token,
                               UsageLedger ledger, Consumer<String> reply) {
        TaskState state = task.getState();
        if (!state.isTerminal()) throw new IllegalArgumentException("原任务尚未终态");
        TaskRecord.SkillSelection choice = task.skillSelection();
        if (choice == null || !choice.skillRef().equals(ref) || !choice.version().equals(version)
                || !choice.companionId().equals(tasks.companionId().toString())
                || !choice.token().equals(token) || !choice.bodyRef().equals(skill.bodyRef())
                || !choice.bodyDigest().equals(digest)) {
            throw new IllegalArgumentException("无原完整回复绑定，或同伴/版/正文/身份不匹配");
        }
        UsageLedger.Outcome outcome = switch (state) {
            case SUCCESS -> UsageLedger.Outcome.SUCCESS;
            // TaskRecord deadline exhaustion is a failure; ACX wall-clock termination has its own contract.
            case FAILED, TIMEOUT -> UsageLedger.Outcome.FAIL;
            case CANCELLED -> UsageLedger.Outcome.CANCELED;
            default -> throw new IllegalArgumentException("未知原任务终态");
        };
        JsonObject detail = new JsonObject();
        detail.addProperty("task_id", task.publicId());
        detail.addProperty("task_tool_call_id", task.getToolCallId());
        detail.addProperty("companion_id", choice.companionId());
        detail.addProperty("world_id", token.identity().get("world_id"));
        detail.addProperty("server_session", token.identity().get("server_session"));
        detail.addProperty("epoch", token.epoch());
        detail.addProperty("skill_ref", ref);
        detail.addProperty("version", version);
        JsonArray taskIdentity = new JsonArray();
        taskIdentity.add(token.identity().get("world_id"));
        taskIdentity.add(token.identity().get("server_session"));
        taskIdentity.add(token.epoch());
        taskIdentity.add(choice.companionId());
        taskIdentity.add(task.publicId());
        taskIdentity.add(task.getToolCallId());
        // Reversible original host fields; never accept a caller-supplied fact identity.
        String encodedTask = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(taskIdentity.toString().getBytes(StandardCharsets.UTF_8));
        detail.add("task_identity", taskIdentity);
        // Stable original task key, independent of report call id, time and terminal state.
        String factKey = digest(detail.toString());
        String prefix = "task-skill:" + factKey + ":";
        String source = prefix + state.name();
        detail.addProperty("task_fact_key", factKey);
        detail.addProperty("selection_call_id", choice.selectionCallId());
        detail.addProperty("body_ref", choice.bodyRef());
        detail.addProperty("body_digest", choice.bodyDigest());
        detail.addProperty("terminal_state", state.name());
        detail.addProperty("meaning", "模型选择+服务端完整回复+原任务终态，待审候选；非因果有效");
        // Existing ledger cancellation semantics are per artifact. Isolate each original task fact.
        String artifactId = ref + "@" + version + "#task=" + encodedTask;
        UsageLedger.Entry result = null;
        synchronized (ledger) {
            if (!ledger.readable()) throw new IllegalStateException("使用账本不可读，不能判断幂等");
            for (UsageLedger.Entry row : ledger.all()) {
                if (row.source().startsWith(prefix)) {
                    if (!row.source().equals(source) || !row.artifactId().equals(artifactId)
                            || row.phase() != UsageLedger.Phase.RESULT
                            || !row.companionId().equals(choice.companionId())) {
                        throw new IllegalStateException("同一原任务已有冲突终态，拒绝覆盖");
                    }
                    result = row;
                }
            }
            if (result == null) {
                result = ledger.append(artifactId, skill.kind().name(), skill.name(), UsageLedger.Phase.RESULT,
                        outcome, detail.toString(), source, choice.companionId());
            }
        }
        Map<String, Object> data = new LinkedHashMap<>(ledger.toMap(List.of(result)));
        data.put("task_id", task.publicId());
        data.put("terminal_state", state.name());
        data.put("original_task_outcome", outcome.name());
        data.put("note", "仅记待审候选；未声明有效性或提升证据。同技能不同原任务独立记账。");
        send(reply, TaskResult.ok("原任务结果待审候选", data).toJson());
    }

    private static boolean send(Consumer<String> reply, String json) {
        try {
            reply.accept(json);
            return true;
        } catch (RuntimeException transportFailure) {
            return false;
        }
    }

    private static String required(JsonObject args, String key) {
        if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()
                || !args.getAsJsonPrimitive(key).isString() || args.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException("需要 " + key);
        }
        return args.get(key).getAsString();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
