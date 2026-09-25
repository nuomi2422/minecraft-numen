package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * 调试工具（验证专用）：让同伴**直接死亡一次**，用于验证"死亡→复活→回收"链路（V3）。
 *
 * <p>仅在验证阶段使用：把 HP 设为 0 并走原版死亡流程（触发 CompanionEvent.DEATH →
 * 资产失效/历史 LOST/重生点复活）。默认拒绝，除非显式传 confirm=true，防误用。
 */
final class RddDebugKillTool implements NumenTool {
    @Override public String name() { return "debug_kill"; }

    @Override public String description() {
        return "调试/验证专用：让同伴立即死亡一次（走原版死亡流程，触发资产失效与重生）。" +
                "用于验证死亡回收闭环。必须显式 confirm=true，否则拒绝。正常游戏不要用。";
    }

    @Override public Map<String, Object> parameterSchema() {
        return Schema.object().string("confirm", "必须为 true 才执行（防误用）。").build();
    }

    @Override public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            boolean confirm = args != null && args.has("confirm")
                    && "true".equalsIgnoreCase(args.get("confirm").getAsString());
            if (!confirm) {
                reply.accept(com.dwinovo.numen.task.TaskResult.fail("debug_kill refused: confirm=true required").toJson());
                return;
            }
            // 走原版死亡：直接造成致命伤害（伤害源=虚空，避免归因混淆）
            var level = companion.level();
            var src = level.damageSources().genericKill();
            companion.hurt(src, Float.MAX_VALUE);
            reply.accept(com.dwinovo.numen.task.TaskResult.ok(
                    "debug_kill executed (lethal damage applied)", Map.of("hp", companion.getHealth())).toJson());
        } catch (RuntimeException ex) {
            reply.accept(com.dwinovo.numen.task.TaskResult.fail("debug_kill failed: " + ex.getMessage()).toJson());
        }
    }
}
