package com.dwinovo.numen.plugins.selfcompile.generated;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 把同伴直接传送到指定坐标（生存用「瞬移」不是玩法，是**脱困**）。
 *
 * <p>为什么需要（2026-10-23）：同伴多次被困在「挖穿的竖井/洞穴/树顶够不着」的位置，
 * 而 `goto` 只会寻路、不会爬垂直面，于是每次都要重开一局。
 * 有了它，「回到地面 / 回基地 / 去远处找木头」都变成一次调用。
 *
 * <p>边界：只改位置，不改世界；传送前会先检查目标区块能否加载（失败就明确报错，不静默）。
 */
public final class RddTeleportTool implements NumenTool {

    @Override
    public String name() {
        return "rdd_teleport";
    }

    @Override
    public String description() {
        return "把同伴传送到 (x,y,z)。用于脱困（掉进竖井、卡在洞里）或快速到达远处。"
                + "只改位置、不改世界；目标区块加载失败会明确报错。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("x", Map.of("type", "integer"));
        props.put("y", Map.of("type", "integer"));
        props.put("z", Map.of("type", "integer"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("x", "y", "z"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        if (args == null || !args.has("x") || !args.has("y") || !args.has("z")) {
            reply.accept(TaskResult.fail("需要 x/y/z").toJson());
            return;
        }
        double x = args.get("x").getAsDouble();
        double y = args.get("y").getAsDouble();
        double z = args.get("z").getAsDouble();
        if (!(companion.level() instanceof ServerLevel level)) {
            reply.accept(TaskResult.fail("需要服务端世界").toJson());
            return;
        }
        var before = companion.position();
        boolean loaded = level.isLoaded(net.minecraft.core.BlockPos.containing(x, y, z));
        if (!loaded) {
            reply.accept(TaskResult.fail("目标区块未加载：" + (int) x + "," + (int) y + "," + (int) z
                    + "（先在附近用 goto 或换一个坐标）").toJson());
            return;
        }
        companion.teleportTo(level, x, y, z, companion.getYRot(), companion.getXRot());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", Map.of("x", before.x, "y", before.y, "z", before.z));
        data.put("to", Map.of("x", companion.getX(), "y", companion.getY(), "z", companion.getZ()));
        reply.accept(TaskResult.ok("已传送", data).toJson());
    }
}
