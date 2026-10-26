package com.dwinovo.numen.plugins.worldstate.probe.impl;

import com.dwinovo.numen.plugins.worldstate.probe.ProbeContext;
import com.dwinovo.numen.plugins.worldstate.probe.ProbeResult;
import com.dwinovo.numen.plugins.worldstate.probe.WorldProbe;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.WorldData;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 难度/硬核/难度锁定/默认模式：判「和平模式不刷怪」的第一站。 */
public final class DifficultyProbe implements WorldProbe {

    @Override public String id() { return "difficulty"; }

    @Override public String title() { return "Server difficulty and hardcore"; }

    @Override public String description() {
        return "Read the server difficulty (peaceful/easy/normal/hard), hardcore flag, "
                + "difficulty lock, and default game mode. Use before diagnosing 'no hostiles spawned'.";
    }

    @Override public List<String> returns() {
        return List.of("difficulty", "hardcore", "difficulty_locked", "default_game_mode");
    }

    @Override
    public ProbeResult collect(ProbeContext ctx) {
        MinecraftServer server = ctx.server();
        if (server == null) {
            return ProbeResult.failed("NOT_FOUND", "not attached to a server");
        }
        WorldData data = server.getWorldData();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("difficulty", data.getDifficulty().name().toLowerCase(Locale.ROOT));
        out.put("hardcore", server.isHardcore());
        out.put("difficulty_locked", data.isDifficultyLocked());
        out.put("default_game_mode", server.getDefaultGameType().getName());
        return ProbeResult.complete(out);
    }
}
