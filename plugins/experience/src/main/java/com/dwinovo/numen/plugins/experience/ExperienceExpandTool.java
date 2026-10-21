package com.dwinovo.numen.plugins.experience;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.experience.core.ExperienceMemory;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * G5f：按 {@code EntryRef} 展开一条经验的<b>正文</b>（只读，IC10「展开不等于执行」）。
 *
 * <p><b>为什么要有它</b>：{@code <retrieved_knowledge>} 里只有标题（正文有预算上限），
 * AI 想细看某一条时必须有「只读展开」的口 —— 否则它要么靠猜，要么去调真正的工具
 * （那是执行，不是阅读）。这个工具<b>不派任务、不写库、不产生执行票据</b>，
 * 只把 {@code MinimalRetrievalService.expand} 的结果原样交回去。
 *
 * <p><b>失败必须如实</b>：{@code NO_OWNER}/{@code NOT_FOUND}/{@code SOURCE_NULL}
 * 都是失败，绝不用空正文冒充成功（那会让 AI 以为「这条经验没内容」）。
 */
final class ExperienceExpandTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final int DEFAULT_MAX_CHARS = 800;
    private static final int HARD_MAX_CHARS = 4000;

    @Override
    public String name() {
        return "experience_expand";
    }

    @Override
    public String description() {
        return "Read the full body of one experience by its ref (from <retrieved_knowledge> or experience_recall). "
                + "READ-ONLY: it does not run anything, does not start a task, and grants no tool permission. "
                + "Refs look like 'exp:execution|<title>'. Tool-catalog refs ('tool:...') are not supported yet "
                + "and fail with NO_OWNER.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("ref", "Entry ref, e.g. exp:execution|某条经验的标题。")
                .optionalInteger("max_chars", "正文最多返回多少字符（默认 800，上限 4000）。", 1, HARD_MAX_CHARS)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            Input in = GSON.fromJson(args, Input.class);
            if (in == null || in.ref() == null || in.ref().isBlank()) {
                reply.accept(TaskResult.fail("experience_expand requires ref (e.g. exp:execution|<title>)").toJson());
                return;
            }
            int max = in.max_chars() == null ? DEFAULT_MAX_CHARS
                    : Math.max(1, Math.min(HARD_MAX_CHARS, in.max_chars()));
            ExperienceMemory memory = ExperiencePlugin.memory(companion.getUUID());
            ExperienceStoreRetrievalBridge.Expanded r =
                    ExperienceStoreRetrievalBridge.expand(memory, in.ref(), max);
            if (!r.ok()) {
                reply.accept(TaskResult.fail(r.code() + ": " + r.message()).toJson());
                return;
            }
            reply.accept(TaskResult.ok(r.body(), java.util.Map.of(
                    "ref", r.ref(),
                    "chars", r.chars(),
                    "truncated", r.truncated(),
                    "read_only", true)).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("experience_expand failed: " + ex.getMessage()).toJson());
        }
    }

    private record Input(String ref, Integer max_chars) {}
}
