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
 * {@code learner_funnel}：检索漏斗的只读视图（158 §3.4 / 159 H）。
 *
 * <h2>它回答的问题</h2>
 * 一条经验/Skill 到底走到了哪一阶 —— 是**不适用**、适用但没召回、召回了没呈现、
 * 呈现了没展开、展开了没采用、采用了没执行，还是执行了没效果。
 * 这七种情况的修法完全不同，混成一个「没用」等于什么都没说。
 *
 * <h2>怎么算的</h2>
 * 不回放"记忆里的状态"，而是每次调用现读两本现有日志
 * （`context.jsonl` 的 `retrieval_snapshot` + `expmem.jsonl`），见 {@code FunnelReplay}。
 * 这样面板反映的永远是<b>日志里的事实</b>，不是某个进程内的缓存。
 *
 * <h2>边界</h2>
 * 只读：不改世界、不写账本、不触发检索。`skipped_lines` 与 `unknown_reasons` 一律透出 ——
 * 读不全就要说读不全。
 */
final class LearnerFunnelTool implements NumenTool {

    @Override
    public String name() {
        return "learner_funnel";
    }

    @Override
    public NumenTool.Residency residency() {
        return NumenTool.Residency.DEFERRED;
    }

    @Override
    public String description() {
        return "READ-ONLY view of the retrieval funnel: APPLICABLE -> RECALLED -> PRESENTED -> "
                + "EXPANDED -> ADOPTED -> EXECUTED -> EFFECT. action=summary 给七阶计数（执行者/学习者分开）；"
                + "action=stopped + entry_ref 给「这条停在哪一阶」的一句话结论。"
                + "它从现有日志回放（retrieval_snapshot + expmem），不改世界、不写账本。"
                + "skipped_lines>0 表示有行没读进来：那时 0 不代表「没发生」。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalString("action", "summary | stopped")
                .optionalString("entry_ref", "action=stopped 时要查的条目 id")
                .optionalString("window_minutes", "回放时间窗（分钟，默认 30，夹在 1~720）。"
                        + "不同窗口下的七阶计数不可直接比较。")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        String action = str(args, "action");
        Path dir = LearnerPlugin.configDir();
        long now = System.currentTimeMillis();
        try {
            if (dir == null) {
                reply.accept(TaskResult.fail("learner_funnel: configDir 未就绪").toJson());
                return;
            }
            var ctx = com.dwinovo.numen.plugins.learner.blocks.ContextFileReader.read(dir);
            var exp = com.dwinovo.numen.plugins.learner.blocks.ContextFileReader.readAt(
                    dir, "monitor/expmem.jsonl",
                    com.dwinovo.numen.plugins.learner.blocks.ContextFileReader.TAIL_BYTES,
                    com.dwinovo.numen.plugins.learner.blocks.ContextFileReader.MAX_LINES);
            // ⚠ 必须按**时间窗**回放，不能各读各的尾部字节：
            //   context 每行含整段 XML、expmem 每行很小，同样取 4MB 会落在两段完全不同的时间，
            //   于是出现 ADOPTED > RECALLED 这种荒谬计数（164 §8 实测）。
            long windowMinutes = windowMinutesOf(args);
            long from = now - windowMinutes * 60_000L;
            var replay = com.dwinovo.numen.plugins.learner.funnel.FunnelReplay.replay(
                    ctx.lines(), exp.lines(), from, now, now);
            var ledger = com.dwinovo.numen.plugins.learner.funnel.FunnelReport.ledgerOf(replay);

            Map<String, Object> data;
            switch (action) {
                case "summary" -> data = com.dwinovo.numen.plugins.learner.funnel.FunnelReport.summary(ledger, replay);
                case "stopped" -> data = com.dwinovo.numen.plugins.learner.funnel.FunnelReport.stopped(
                        ledger, str(args, "entry_ref"),
                        companion == null ? com.dwinovo.numen.plugins.learner.funnel.FunnelLedger.Caller.EXECUTOR
                                : com.dwinovo.numen.plugins.learner.funnel.FunnelLedger.Caller.EXECUTOR);
                default -> {
                    reply.accept(TaskResult.fail("learner_funnel: unknown action '" + action
                            + "'（可用 summary/stopped）").toJson());
                    return;
                }
            }
            // 窗口必须透出：同一个"七阶计数"在 30 分钟窗和 24 小时窗里是两件事。
            java.util.Map<String, Object> win = new java.util.LinkedHashMap<>();
            win.put("window_minutes", windowMinutes);
            win.put("from", from);
            win.put("to", now);
            data.put("window", win);

            // 两本日志本体的可读性也透出：读不到与没有内容必须分开。
            data.put("context_readable", ctx.ok());
            if (!ctx.ok()) {
                data.put("context_error", ctx.error());
            }
            data.put("expmem_readable", exp.ok());
            reply.accept(TaskResult.ok("learner funnel", data).toJson());
        } catch (RuntimeException e) {
            reply.accept(TaskResult.fail("learner_funnel failed: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage()).toJson());
        }
    }

    /** 窗口分钟数：默认 30，夹在 1~720 之间（防止有人填 99999 把整份日志读进来）。 */
    private static long windowMinutesOf(JsonObject args) {
        if (args == null || !args.has("window_minutes") || !args.get("window_minutes").isJsonPrimitive()) {
            return 30L;
        }
        try {
            long v = args.get("window_minutes").getAsLong();
            return Math.max(1L, Math.min(720L, v));
        } catch (RuntimeException e) {
            return 30L;
        }
    }

    private static String str(JsonObject args, String key) {
        if (args == null || !args.has(key) || !args.get(key).isJsonPrimitive()) {
            return "";
        }
        return args.get(key).getAsString().trim();
    }
}
