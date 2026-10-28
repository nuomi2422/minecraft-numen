package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code rdd_decline_side}：执行 AI 主动放弃当前支线（2026-10-28，主人第三点）。
 *
 * <h2>为什么需要它</h2>
 * 主人原话：「这个支线任务是发给你了，但是你要不要去捡是你决定的……万一那里很危险，
 * 我回去了也没有用。我没有装备，我依然减不了，要给他提示啊，他可以选择跳过」。
 *
 * <p>此前支线上下文写的是「你现在<b>最高优先</b>的目标」，工具面上也没有任何拒绝的出口 ——
 * 执行侧只能照着办。光改文案不够：拒绝之后得有地方落账（事件）、有地方收（回主线）、
 * 还得保证「今天别再问我同一件事」。这三件都在 {@code SideTaskHost.decline} 里做完，
 * 本工具只负责把它变成一个她真能调的按钮。
 *
 * <h2>边界</h2>
 * 只拒绝<b>支线</b>，不碰主线；主线的「这步做不到」是 {@code report_task_concern}（COUNTER/PAUSE）。
 * 支线状态记 {@code DECLINED}（不是 CANCELLED、不是 FAILED）——两者在复盘里含义相反。
 */
final class RddDeclineSideTool implements NumenTool {

    @Override
    public String name() {
        return "rdd_decline_side";
    }

    @Override
    public String description() {
        return "Decline the CURRENT side task (sleep / recovery / learner nudge) when going would be useless or lethal: "
                + "no weapon, mobs at the bed, no bed within reach, drops already unreachable, or you know you cannot finish it. "
                + "A side task is a SUGGESTION with a cost, not an order — declining is a legitimate choice, and it must be "
                + "explained with a concrete reason (what you observed). "
                + "Effects: the side task ends as DECLINED (not cancelled, not failed), the mainline unfreezes immediately, "
                + "and the same side-task type will not be offered again today. "
                + "Do not use it to dodge work you could do; use it when the trip itself is the problem.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("reason", "What you observed that makes this side task not worth it (mobs / no tools / already gone).")
                .string("expected_type", "Optional: the side task type you are declining (from the <side_task type=...> tag), "
                        + "so a stale choice cannot decline the wrong one.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            String reason = str(args, "reason");
            if (reason.isBlank()) {
                throw new IllegalArgumentException("reason required: say what you saw, or the decline is not reviewable");
            }
            String expectedType = str(args, "expected_type");
            String currentType = SideTaskHost.currentTypeId(companion.getUUID());
            if (currentType == null) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "no active side task; nothing to decline").toJson());
                return;
            }
            if (!expectedType.isBlank() && !expectedType.equals(currentType)) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "decline refused: side task type changed (current=" + currentType + ")").toJson());
                return;
            }
            boolean declined = SideTaskHost.decline(companion.getUUID(), reason);
            if (!declined) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail(
                        "decline refused: side task already ended").toJson());
                return;
            }
            reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                    "side task declined (" + currentType + "); mainline resumed — do not wait for it again today",
                    Map.of("type", currentType)).toJson());
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("decline failed: " + ex.getMessage()).toJson());
        }
    }

    private static String str(JsonObject args, String key) {
        return args != null && args.has(key) && !args.get(key).isJsonNull() ? args.get(key).getAsString() : "";
    }
}
