package com.dwinovo.numen.plugins.selfcompile.generated;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 清理「挡在目标前面的软方块」（树叶/草/花/藤），让 `mine` 的准星真正落到目标上。
 *
 * <p>为什么需要（2026-10-23）：`mine` 只对**准星正对着的方块**生效；树上的原木常被树叶挡住，
 * 于是 `mine` 反复 FAILED 而回执不说原因。把这件事做成**一次调用**，
 * 才能写进 AC 里变成确定性步骤（而不是靠模型记得先打叶子）。
 *
 * <p>边界：**只拆软的**（_leaves / grass / flower / vine / fern）。石头、木头这类硬挡路**不动**，
 * 直接把「挡住了且拆不动」如实报回来 —— 不替玩家做危险决定。
 */
public final class RddClearAimTool implements NumenTool {

    private static final int MAX_CLEAR = 3;

    @Override
    public String name() {
        return "rdd_clear_aim";
    }

    @Override
    public String description() {
        return "从眼睛看向 (x,y,z)，把挡在前面的树叶/草/花/藤拆掉（最多 3 层），返回拆了什么。"
                + "用于 mine 之前清遮挡；硬方块挡住会明确报出来，不会去拆。";
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
        if (!(companion.level() instanceof ServerLevel level)) {
            reply.accept(TaskResult.fail("需要服务端世界").toJson());
            return;
        }
        int tx = args.get("x").getAsInt();
        int ty = args.get("y").getAsInt();
        int tz = args.get("z").getAsInt();
        List<String> removed = new ArrayList<>();
        String blockedBy = null;
        for (int i = 0; i < MAX_CLEAR; i++) {
            Vec3 eye = companion.getEyePosition();
            Vec3 end = new Vec3(tx + 0.5, ty + 0.5, tz + 0.5);
            var clip = level.clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.NONE, companion));
            if (clip.getType() == HitResult.Type.MISS) break;
            var pos = clip.getBlockPos();
            if (pos.getX() == tx && pos.getY() == ty && pos.getZ() == tz) break;
            BlockState st = level.getBlockState(pos);
            String id = BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
            if (!isSoft(id)) {
                blockedBy = id + " @" + pos.toShortString();
                break;
            }
            level.destroyBlock(pos, false);
            removed.add(id + " @" + pos.toShortString());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("removed", removed);
        data.put("blocked_by_hard", blockedBy);
        data.put("aim_point", tx + "," + ty + "," + tz);
        reply.accept(TaskResult.ok("清遮挡：拆了 " + removed.size() + " 个"
                + (blockedBy == null ? "" : "；仍被硬方块挡住 " + blockedBy), data).toJson());
    }

    private static boolean isSoft(String id) {
        return id.endsWith("_leaves") || id.contains("grass") || id.contains("flower")
                || id.contains("vine") || id.contains("fern") || id.contains("sapling");
    }
}
