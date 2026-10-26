package com.dwinovo.numen.plugins.worldstate.probe.impl;

import com.dwinovo.numen.plugins.worldstate.probe.ProbeContext;
import com.dwinovo.numen.plugins.worldstate.probe.ProbeResult;
import com.dwinovo.numen.plugins.worldstate.probe.WorldProbe;
import net.minecraft.server.MinecraftServer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 模拟距离/视距：解释「远处实体没进来」「区块没加载」的服务器侧参数。 */
public final class DistancesProbe implements WorldProbe {

    @Override public String id() { return "distances"; }

    @Override public String title() { return "Simulation and view distance"; }

    @Override public String description() {
        return "Read the server simulation distance and view distance (chunks). Use when entity "
                + "counts or block reads differ from what a player sees.";
    }

    @Override public List<String> returns() {
        return List.of("simulation_distance", "view_distance");
    }

    @Override
    public ProbeResult collect(ProbeContext ctx) {
        MinecraftServer server = ctx.server();
        if (server == null) {
            return ProbeResult.failed("NOT_FOUND", "not attached to a server");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("simulation_distance", server.getPlayerList().getSimulationDistance());
        out.put("view_distance", server.getPlayerList().getViewDistance());
        return ProbeResult.complete(out);
    }
}
