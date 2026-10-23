package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 调试用：点名一条**恢复支线**（等价于"重要度确认丢了东西"那一刻发生的事）。
 *
 * <p>为什么需要（2026-10-23）：恢复支线的真实触发条件是「非死亡的缺失型重规划」，
 * 在实机上很难按需制造；没有这个入口就只能等，没法验证
 * {@code side_task_created(type=recovery)} / 主线冻结 / 完成后自动恢复 这条链。
 *
 * <p>边界：只点名，不直接造支线；引擎仍按自己的节拍与规则决定开不开。
 */
public final class RddDebugRecoveryTool implements NumenTool {

    @Override
    public String name() {
        return "rdd_debug_recovery";
    }

    @Override
    public String description() {
        return "调试：点名一条恢复支线去取回指定物品（item=物品 id，count=需要数量，默认 1）。"
                + "用于验证恢复支线的创建/冻结/完成链路。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("item", Map.of("type", "string", "description", "物品 id，如 minecraft:cobblestone"));
        props.put("count", Map.of("type", "integer", "minimum", 1, "description", "需要数量，默认 1"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", java.util.List.of("item"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        if (args == null || !args.has("item")) {
            reply.accept(TaskResult.fail("需要 item").toJson());
            return;
        }
        String item = args.get("item").getAsString();
        int count = args.has("count") ? Math.max(1, args.get("count").getAsInt()) : 1;
        com.dwinovo.numen.rdd.side.recovery.RecoverySideTaskTrigger.expect(companion.getUUID(), item, count);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("item", item);
        data.put("count", count);
        data.put("active_now", SideTaskHost.isActive(companion.getUUID()));
        reply.accept(TaskResult.ok("已点名恢复支线：" + item + " ×" + count
                + "（引擎下一拍决定是否创建）", data).toJson());
    }
}
