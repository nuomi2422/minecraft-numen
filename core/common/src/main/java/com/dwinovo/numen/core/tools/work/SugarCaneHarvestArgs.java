package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.core.task.sugarcane.SugarCaneTaskRecord;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** tool 参数 -> SugarCaneTaskRecord 的**生产同源**解析（2026-11-07）。越界=失败，不做 clamp 规范化。 */
public final class SugarCaneHarvestArgs {
    private SugarCaneHarvestArgs() {}

    public static final long WALL_MS_PER_BLOCK = 2_000L;
    public static final long MAX_DEADLINE_MS = 120_000L;

    public record Parsed(SugarCaneTaskRecord record, String error) {}

    public static Parsed parse(JsonObject args, long nowMs) {
        Integer count = integer(args, "count");
        if (count == null) return new Parsed(null, missing(args, "count") ? "count is required (1..64 upper blocks)" : "count must be an integer");
        if (count < 1 || count > SugarCaneTaskRecord.MAX_COUNT)
            return new Parsed(null, "count must be 1.." + SugarCaneTaskRecord.MAX_COUNT + " (got " + count + ")");
        Integer maxTargets = integer(args, "max_targets");
        if (maxTargets == null && present(args, "max_targets")) return new Parsed(null, "max_targets must be an integer");
        int mt = maxTargets == null ? 8 : maxTargets;
        if (mt < 1 || mt > SugarCaneTaskRecord.MAX_TARGETS)
            return new Parsed(null, "max_targets must be 1.." + SugarCaneTaskRecord.MAX_TARGETS + " (got " + mt + ")");
        Integer radius = integer(args, "radius");
        if (radius == null && present(args, "radius")) return new Parsed(null, "radius must be an integer");
        int r = radius == null ? 12 : radius;
        if (r < 1 || r > SugarCaneTaskRecord.MAX_RADIUS)
            return new Parsed(null, "radius must be 1.." + SugarCaneTaskRecord.MAX_RADIUS + " (got " + r + ")");
        long deadline = nowMs + Math.min(MAX_DEADLINE_MS, count * WALL_MS_PER_BLOCK);
        try {
            return new Parsed(new SugarCaneTaskRecord(count, mt, r, deadline, nowMs), "");
        } catch (IllegalArgumentException e) {
            return new Parsed(null, e.getMessage());
        }
    }

    private static boolean present(JsonObject a, String k) {
        return a != null && a.has(k) && !a.get(k).isJsonNull();
    }

    private static boolean missing(JsonObject a, String k) { return !present(a, k); }

    /** 只接受 JSON number 且为整数；字符串/小数/布尔一律 null（由调用方区分缺失与非法）。 */
    private static Integer integer(JsonObject a, String k) {
        if (!present(a, k)) return null;
        JsonElement e = a.get(k);
        if (!e.isJsonPrimitive()) return null;
        JsonPrimitive p = e.getAsJsonPrimitive();
        if (!p.isNumber()) return null;
        double d = p.getAsDouble();
        if (d != Math.rint(d) || d < Integer.MIN_VALUE || d > Integer.MAX_VALUE) return null;
        return (int) d;
    }
}