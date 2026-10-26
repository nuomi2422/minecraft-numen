package com.dwinovo.numen.plugins.worldstate.probe.impl;

import com.dwinovo.numen.plugins.worldstate.probe.ProbeContext;
import com.dwinovo.numen.plugins.worldstate.probe.ProbeResult;
import com.dwinovo.numen.plugins.worldstate.probe.WorldProbe;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 指定坐标的方块光/天空光/是否见天。区块未加载明确失败，不返回 0。 */
public final class LightProbe implements WorldProbe {

    @Override public String id() { return "light"; }

    @Override public String title() { return "Light level at a position"; }

    @Override public String description() {
        return "Read block light, sky light and can_see_sky at x/y/z (defaults to the companion's feet). "
                + "Use when diagnosing mob spawning or cave visibility. Unloaded chunk fails explicitly.";
    }

    @Override public List<String> params() { return List.of("x", "y", "z"); }

    @Override public List<String> returns() {
        return List.of("x", "y", "z", "block_light", "sky_light", "can_see_sky");
    }

    @Override
    public ProbeResult collect(ProbeContext ctx) {
        ServerLevel level = ctx.level();
        if (level == null) {
            return ProbeResult.failed("DIMENSION_MISSING", "dimension not loaded: " + ctx.dimension());
        }
        BlockPos pos = new BlockPos(ctx.x(), ctx.y(), ctx.z());
        if (!level.hasChunkAt(pos)) {
            return ProbeResult.failed("CHUNK_UNLOADED",
                    "chunk at " + pos.toShortString() + " is not loaded; go there first");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("x", pos.getX());
        out.put("y", pos.getY());
        out.put("z", pos.getZ());
        out.put("block_light", level.getBrightness(LightLayer.BLOCK, pos));
        out.put("sky_light", level.getBrightness(LightLayer.SKY, pos));
        out.put("can_see_sky", level.canSeeSky(pos));
        return ProbeResult.complete(out);
    }
}
