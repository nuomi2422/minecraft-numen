package com.dwinovo.numen.plugins.worldstate.probe.impl;

import com.dwinovo.numen.plugins.worldstate.probe.ProbeContext;
import com.dwinovo.numen.plugins.worldstate.probe.ProbeResult;
import com.dwinovo.numen.plugins.worldstate.probe.WorldProbe;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 天气：clear/rain/thunder + 原始布尔值 + 雨强。 */
public final class WeatherProbe implements WorldProbe {

    @Override public String id() { return "weather"; }

    @Override public String title() { return "Weather"; }

    @Override public String description() {
        return "Read clear/rain/thunder, the raw raining/thundering flags and rain level. "
                + "Use when comparing two experiment runs or explaining visibility/spawn differences.";
    }

    @Override public List<String> returns() {
        return List.of("weather", "raining", "thundering", "rain_level");
    }

    @Override
    public ProbeResult collect(ProbeContext ctx) {
        ServerLevel level = ctx.level();
        if (level == null) {
            return ProbeResult.failed("DIMENSION_MISSING", "dimension not loaded: " + ctx.dimension());
        }
        boolean raining = level.isRaining();
        boolean thundering = level.isThundering();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("weather", thundering ? "thunder" : (raining ? "rain" : "clear"));
        out.put("raining", raining);
        out.put("thundering", thundering);
        out.put("rain_level", level.getRainLevel(1.0F));
        return ProbeResult.complete(out);
    }
}
