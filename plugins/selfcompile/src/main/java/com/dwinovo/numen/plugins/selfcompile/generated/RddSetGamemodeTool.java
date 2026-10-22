package com.dwinovo.numen.plugins.selfcompile.generated;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.JsonObject;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 把某个在线玩家切成指定游戏模式（默认把主人切成观察者）。
 *
 * <p>为什么需要：主人明确要求「把我调成观察者，这样不会影响 AI 睡觉」。
 * 键盘通道在这台机器上不稳（发送成功但游戏收不到），所以必须走工具而不是打字。
 *
 * <p><b>刻意做窄</b>：只做「改游戏模式」这一件事，不做通用命令执行。
 * 通用执行器等于把服务器控制台交出去，风险不对等；这里只接受 4 个合法模式名。
 */
public final class RddSetGamemodeTool implements NumenTool {

    @Override
    public String name() {
        return "rdd_set_gamemode";
    }

    @Override
    public String description() {
        return "把某个在线玩家切成指定游戏模式（survival/creative/adventure/spectator）。"
                + "不给 player 就作用于主人（RDDDev）。用于「主人切成观察者，不干扰同伴」。";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("mode", Map.of("type", "string",
                "enum", List.of("survival", "creative", "adventure", "spectator"),
                "description", "目标游戏模式"));
        props.put("player", Map.of("type", "string",
                "description", "玩家名（默认 RDDDev）"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("mode"));
        schema.put("additionalProperties", false);
        return schema;
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        if (args == null || !args.has("mode")) {
            reply.accept(TaskResult.fail("需要 mode（survival/creative/adventure/spectator）").toJson());
            return;
        }
        String modeName = args.get("mode").getAsString().toLowerCase(java.util.Locale.ROOT);
        GameType type = switch (modeName) {
            case "survival" -> GameType.SURVIVAL;
            case "creative" -> GameType.CREATIVE;
            case "adventure" -> GameType.ADVENTURE;
            case "spectator" -> GameType.SPECTATOR;
            default -> null;
        };
        if (type == null) {
            reply.accept(TaskResult.fail("非法模式：" + modeName).toJson());
            return;
        }
        String playerName = args.has("player") ? args.get("player").getAsString() : "RDDDev";
        MinecraftServer server = companion.getServer();
        if (server == null) {
            reply.accept(TaskResult.fail("服务端未就绪").toJson());
            return;
        }
        ServerPlayer target = server.getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            StringBuilder online = new StringBuilder();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (online.length() > 0) online.append(", ");
                online.append(p.getGameProfile().getName());
            }
            reply.accept(TaskResult.fail("找不到在线玩家 " + playerName + "；当前在线："
                    + (online.length() == 0 ? "（无）" : online)).toJson());
            return;
        }
        GameType before = target.gameMode.getGameModeForPlayer();
        target.setGameMode(type);
        GameType after = target.gameMode.getGameModeForPlayer();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("player", playerName);
        data.put("before", before.getName());
        data.put("after", after.getName());
        reply.accept(TaskResult.ok("已把 " + playerName + " 设为 " + after.getName()
                + "（原 " + before.getName() + "）", data).toJson());
    }
}
