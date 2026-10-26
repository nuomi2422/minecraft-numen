package com.dwinovo.numen.plugins.worldstate.probe.impl;

import com.dwinovo.numen.plugins.worldstate.probe.ProbeContext;
import com.dwinovo.numen.plugins.worldstate.probe.ProbeResult;
import com.dwinovo.numen.plugins.worldstate.probe.WorldProbe;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 在线玩家列表：名字/模式/旁观/就寝/OP/维度。
 *
 * <p>这是「睡了不跳夜」的诊断入口：先看有没有非旁观玩家没睡，再谈睡觉实现。
 * 玩家按名字排序，保证同一状态得到同一串。
 */
public final class PlayersProbe implements WorldProbe {

    @Override public String id() { return "players"; }

    @Override public String title() { return "Online players and sleep state"; }

    @Override public String description() {
        return "List every online server player with game mode, spectator flag, sleeping state, "
                + "OP flag and dimension. Use before blaming sleep/day-skip code: one awake "
                + "non-spectator (including the observer) can block the night skip.";
    }

    @Override public List<String> returns() {
        return List.of("count", "players[name,mode,spectator,sleeping,op,dimension]");
    }

    @Override
    public ProbeResult collect(ProbeContext ctx) {
        MinecraftServer server = ctx.server();
        if (server == null) {
            return ProbeResult.failed("NOT_FOUND", "not attached to a server");
        }
        PlayerList list = server.getPlayerList();
        List<ServerPlayer> players = new ArrayList<>(list.getPlayers());
        players.sort(Comparator.comparing(p -> p.getName().getString()));

        List<Map<String, Object>> rows = new ArrayList<>();
        for (ServerPlayer p : players) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", p.getName().getString());
            row.put("uuid", p.getStringUUID());
            row.put("mode", p.gameMode.getGameModeForPlayer().getName());
            row.put("spectator", p.isSpectator());
            row.put("sleeping", p.isSleeping());
            row.put("op", list.isOp(p.getGameProfile()));
            row.put("dimension", p.level().dimension().location().toString());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", rows.size());
        out.put("players", rows);
        return ProbeResult.complete(out);
    }
}
