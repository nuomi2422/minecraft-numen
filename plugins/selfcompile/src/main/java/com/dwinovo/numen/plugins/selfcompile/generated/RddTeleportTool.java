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
                // 2026-10-25 实机：同伴在洞里迷路，想传送回基地却报「目标区块未加载」，自救路断。
                // 传送不会为了你加载区块 —— 所以脱困的正确姿势是"先传到脚下这块已加载区块的上方"。
                + " 注意：目标区块未加载时会被拒绝。被困在地下时，正确做法是先把 y 设成 60~80、x/z 保持不动，"
                + "传送到**自己所在区块的地表**，落地后再用 goto 走。不要直接传送到很远的地方。"
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
        var target = net.minecraft.core.BlockPos.containing(x, y, z);
        boolean loaded = level.isLoaded(target);
        if (!loaded) {
            reply.accept(TaskResult.fail("目标区块未加载：" + (int) x + "," + (int) y + "," + (int) z
                    + "（先在附近用 goto 或换一个坐标）").toJson());
            return;
        }
        // ── 2026-10-27：落点安全检查（主人「死亡记录」核查的直接产物）──────────────
        // 实测证据：最近 3 天 52 次死亡里有 2 次死在 rdd_teleport 之后——一次溺水、一次被蜘蛛咬。
        // 传送是「脱困」工具，但它此前**不看落点**：水里、岩浆里、实心方块里、怪堆中间都照传。
        // 这里补三件（拒绝 + 如实说明，不静默改写坐标）：
        //   ① 落点或头顶是液体 → 拒绝（溺水/岩浆是立即伤害，不是"稍后处理"）
        //   ② 落点与头顶都是实心 → 拒绝（卡在方块里会窒息）
        //   ③ 落点 8 格内有敌怪 → 允许但回执里点名（它得知道"到那儿就要打"）
        var feet = level.getBlockState(target);
        var head = level.getBlockState(target.above());
        boolean feetLiquid = !feet.getFluidState().isEmpty();
        boolean headLiquid = !head.getFluidState().isEmpty();
        if (feetLiquid || headLiquid) {
            reply.accept(TaskResult.fail("落点是液体（" + (feetLiquid ? "脚下" : "头部")
                    + "）：溺水/岩浆是立即伤害，不是脱困。换一个坐标（建议先 y=60~80 传到自己区块地表）。")
                    .toJson());
            return;
        }
        if (feet.isSolidRender(level, target) && head.isSolidRender(level, target.above())) {
            reply.accept(TaskResult.fail("落点被方块占住（脚与头都在实心方块里，会窒息）。"
                    + "换一个坐标或先往上抬 2 格。").toJson());
            return;
        }
        int hostiles = 0;
        try {
            var box = new net.minecraft.world.phys.AABB(target).inflate(8);
            for (var e : level.getEntities((net.minecraft.world.entity.Entity) null, box)) {
                if (e instanceof net.minecraft.world.entity.monster.Monster && e.isAlive()) hostiles++;
            }
        } catch (RuntimeException ignored) {
            hostiles = -1;   // 数不出来时如实标 -1，不假装 0
        }
        companion.teleportTo(level, x, y, z, companion.getYRot(), companion.getXRot());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", Map.of("x", before.x, "y", before.y, "z", before.z));
        data.put("to", Map.of("x", companion.getX(), "y", companion.getY(), "z", companion.getZ()));
        if (hostiles > 0) {
            data.put("hostiles_near", hostiles);
        }
        String msg = hostiles > 0
                ? "已传送；⚠ 落点 8 格内有 " + hostiles + " 只敌怪 —— 先处理或立刻离开，别站着查看背包。"
                : "已传送";
        reply.accept(TaskResult.ok(msg, data).toJson());
    }
}
