package com.dwinovo.numen.plugins.worldstate.probe.impl;

import com.dwinovo.numen.plugins.worldstate.probe.ProbeContext;
import com.dwinovo.numen.plugins.worldstate.probe.ProbeResult;
import com.dwinovo.numen.plugins.worldstate.probe.WorldProbe;
import net.minecraft.server.level.ServerLevel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 一天内时刻/天数/是否白天/月相。时间桶用于实验对照键，精确 tick 也保留。 */
public final class TimeProbe implements WorldProbe {

    @Override public String id() { return "time"; }

    @Override public String title() { return "World time and day phase"; }

    @Override public String description() {
        return "Read day count, time of day (0..23999), a 24-bucket phase, is_day, moon phase "
                + "and whether the dimension has a fixed time. Use before diagnosing 'night did not skip'.";
    }

    @Override public List<String> returns() {
        return List.of("day", "time_of_day", "time_bucket", "is_day", "moon_phase", "has_fixed_time");
    }

    @Override
    public ProbeResult collect(ProbeContext ctx) {
        ServerLevel level = ctx.level();
        if (level == null) {
            return ProbeResult.failed("DIMENSION_MISSING", "dimension not loaded: " + ctx.dimension());
        }
        long dayTime = level.getDayTime();
        long timeOfDay = Math.floorMod(dayTime, 24000L);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("day", Math.floorDiv(dayTime, 24000L));
        out.put("time_of_day", timeOfDay);
        out.put("time_bucket", (int) (timeOfDay / 1000L));
        out.put("is_day", level.isDay());
        out.put("moon_phase", level.dimensionType().moonPhase(dayTime));
        out.put("has_fixed_time", level.dimensionType().hasFixedTime());
        out.put("day_time", dayTime);
        return ProbeResult.complete(out);
    }
}
