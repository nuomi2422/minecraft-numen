package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.nio.file.Path;
import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code learner_blocks}：按需读取一条学习样本的环境块（159 D 的出口）。
 *
 * <h2>它只做一件事</h2>
 * 把 {@code sample_open / block_list / block_expand / timeline_next / timeline_previous} 暴露给学习者，
 * 让「它当时看到了什么」变成可以真的去查，而不是靠复盘时的回忆。
 *
 * <h2>为什么是「先 open 再多次调用」</h2>
 * 五个动作必须共享同一个上下文（哪条样本、展开过哪些块、翻页锚点）。
 * 每次调用都重开会话，就会出现「展开用的样本和列目录用的样本不是同一条」——
 * 那种错在回执里看起来完全正常。
 *
 * <h2>三条边界</h2>
 * <ul>
 *   <li><b>只读</b>：不改世界、不派动作、不写样本；</li>
 *   <li><b>不跨同伴</b>：A 打不开 B 的样本（拒绝时不透露任何摘要）；</li>
 *   <li><b>不伪造</b>：没开会话、块不存在、样本不存在，一律显式失败，不返回空壳。</li>
 * </ul>
 */
final class LearnerBlocksTool implements NumenTool {

    /** 会话按同伴隔离；进程内一份。 */
    private static final com.dwinovo.numen.plugins.learner.blocks.BlockSessionRegistry REGISTRY =
            new com.dwinovo.numen.plugins.learner.blocks.BlockSessionRegistry();

    @Override
    public String name() {
        return "learner_blocks";
    }

    @Override
    public NumenTool.Residency residency() {
        return NumenTool.Residency.DEFERRED;
    }

    @Override
    public String description() {
        return "READ-ONLY access to the environment blocks of one learning sample. "
                + "action=open(sample_id) 打开一个样本的读取会话；list 看块目录（只有摘要）；"
                + "expand(block_id) 读正文；next/previous(from_block_id) 沿时间线翻页；close 结束会话。"
                + "结束时会自动把**没有展开**的块记成跳过 —— 也就是说没展开就等于没读。"
                + "它不改变世界、不派动作、不写样本；别的同伴的样本打不开。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("action", "open | list | expand | next | previous | close")
                .optionalString("sample_id", "action=open 时要读的样本 id")
                .optionalString("block_id", "action=expand 时要展开的块 id")
                .optionalString("from_block_id", "action=next/previous 的锚点块 id")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        String companionId = companion == null ? "" : companion.getUUID().toString();
        String action = str(args, "action");
        Path dir = LearnerPlugin.configDir();
        long now = System.currentTimeMillis();
        try {
            if (dir == null) {
                reply.accept(TaskResult.fail("learner_blocks: configDir 未就绪").toJson());
                return;
            }
            var outcome = switch (action) {
                case "open" -> REGISTRY.open(companionId, str(args, "sample_id"),
                        com.dwinovo.numen.plugins.learner.sample.SampleIntakeRuntime.shared(dir).storeReadOnly(),
                        com.dwinovo.numen.plugins.learner.blocks.ContextFileReader.read(dir).lines(),
                        now);
                case "list" -> REGISTRY.list(companionId, now);
                case "expand" -> REGISTRY.expand(companionId, str(args, "block_id"), now);
                case "next" -> REGISTRY.step(companionId, str(args, "from_block_id"), true, now);
                case "previous" -> REGISTRY.step(companionId, str(args, "from_block_id"), false, now);
                case "close" -> REGISTRY.close(companionId, now);
                default -> com.dwinovo.numen.plugins.learner.blocks.BlockSessionRegistry.Outcome.fail(
                        "unknown action: '" + action + "'（可用 open/list/expand/next/previous/close）");
            };
            if (outcome.ok()) {
                reply.accept(TaskResult.ok("learner blocks", outcome.data()).toJson());
            } else {
                reply.accept(TaskResult.fail("learner_blocks: " + outcome.error()).toJson());
            }
        } catch (RuntimeException | java.io.IOException e) {
            // 学习层读不出来不许炸工具：显式失败，让调用方知道"没读到"而不是"没有"。
            reply.accept(TaskResult.fail("learner_blocks failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage()).toJson());
        }
    }

    private static String str(JsonObject args, String key) {
        if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
            return "";
        }
        return args.get(key).getAsString().trim();
    }
}
