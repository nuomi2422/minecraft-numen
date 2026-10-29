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
 * H1.1（104 号）：**只读**返回同伴的重生点（床锚）坐标与维度，供实验台在起跑前判「一死会不会掉进虚空」。
 *
 * <p>为什么必须单独一个工具：`get_self_status` 只报当前位置，而 2026-10-29 实测事故是
 * 「人站在 y=64 的实体方块上，但重生点被钉在末地虚空 (163,-269,681)」——只看当前位置抓不到。
 *
 * <p>只读：不写库、不改状态、不派任务。原版 {@code getRespawnPosition()} 只给坐标，
 * 维度必须另取 {@code getRespawnDimension()}（不同维度的同名坐标是合法的）。
 */
final class RddRespawnInfoTool implements NumenTool {

    @Override
    public String name() {
        return "rdd_respawn_info";
    }

    @Override
    public String description() {
        return "Read-only: the companion's respawn point (coordinates + dimension) and whether it is bound. "
                + "Used by the experiment bench before a run to tell whether dying would drop it into the void. "
                + "Changes nothing.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.none();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        try {
            if (companion == null) {
                reply.accept(TaskResult.fail("rdd_respawn_info requires companion").toJson());
                return;
            }
            net.minecraft.core.BlockPos pos = companion.getRespawnPosition();
            boolean bound = pos != null && !pos.equals(net.minecraft.core.BlockPos.ZERO);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("bound", bound);
            data.put("dimension", companion.getRespawnDimension() == null
                    ? "" : companion.getRespawnDimension().location().toString());
            if (bound) {
                data.put("x", pos.getX());
                data.put("y", pos.getY());
                data.put("z", pos.getZ());
            }
            data.put("currentDimension", companion.level() == null
                    ? "" : companion.level().dimension().location().toString());
            reply.accept(TaskResult.ok("respawn info", data).toJson());
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("rdd_respawn_info failed: " + ex).toJson());
        }
    }
}
