package com.dwinovo.numen.plugins.selfcompile.generated;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.Difficulty;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 改世界难度（开发期用 peaceful 避免「裸装被僵尸打死」这类无意义损失）。
 *
 * <p>为什么需要：2026-10-23 03:35 同伴在地下被僵尸打死，铁镐铁剑全掉。
 * 现在阶段目标是「学会盖房/种田/交易」，不是硬核生存；先降难度，等能力齐了再调回 normal。
 *
 * <p><b>可逆</b>：只接受 4 个合法难度名，随时可以调回去。返回「改前 / 改后」两个值，
 * 不靠回执自证。
 */
public final class RddSetDifficultyTool implements NumenTool {

    @Override
    public String name() {
        return "rdd_set_difficulty";
    }

    @Override
    public String description() {
        return "设置世界难度（peaceful/easy/normal/hard）。开发期用 peaceful 避免被怪打死。返回改前/改后。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("difficulty", Map.of("type", "string",
                "enum", List.of("peaceful", "easy", "normal", "hard"),
                "description", "目标难度"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("difficulty"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        if (args == null || !args.has("difficulty")) {
            reply.accept(TaskResult.fail("需要 difficulty").toJson());
            return;
        }
        String name = args.get("difficulty").getAsString().toLowerCase(java.util.Locale.ROOT);
        Difficulty target = switch (name) {
            case "peaceful" -> Difficulty.PEACEFUL;
            case "easy" -> Difficulty.EASY;
            case "normal" -> Difficulty.NORMAL;
            case "hard" -> Difficulty.HARD;
            default -> null;
        };
        if (target == null) {
            reply.accept(TaskResult.fail("非法难度：" + name).toJson());
            return;
        }
        MinecraftServer server = companion.getServer();
        if (server == null) {
            reply.accept(TaskResult.fail("服务端未就绪").toJson());
            return;
        }
        Difficulty before = server.getWorldData().getDifficulty();
        if (before != target) {
            server.setDifficulty(target, true);
        }
        Difficulty after = server.getWorldData().getDifficulty();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("before", before.getKey());
        data.put("after", after.getKey());
        reply.accept(TaskResult.ok("难度 " + before.getKey() + " → " + after.getKey(), data).toJson());
    }
}
