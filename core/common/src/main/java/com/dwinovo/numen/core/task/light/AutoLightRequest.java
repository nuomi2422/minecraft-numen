package com.dwinovo.numen.core.task.light;

import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import java.util.Set;

/** Bounded build op, not a second tool. Strict parsing prevents Gson integer truncation (2026-11-07). */
public record AutoLightRequest(BlockPos min, BlockPos max, int maxLights, int maxScans, int maxTicks) {
    public AutoLightRequest {
        if (min == null || max == null || min.getX() > max.getX() || min.getY() > max.getY()
                || min.getZ() > max.getZ()) throw new IllegalArgumentException("invalid auto_light bounds");
        if (min.getX() < -30000000 || max.getX() > 30000000 || min.getZ() < -30000000
                || max.getZ() > 30000000 || min.getY() < -2048 || max.getY() > 2048)
            throw new IllegalArgumentException("auto_light coordinates out of bounds");
        long width=(long)max.getX()-min.getX()+1, height=(long)max.getY()-min.getY()+1,
                depth=(long)max.getZ()-min.getZ()+1;
        if (width > 16384 || height > 16384 || depth > 16384 || width*height*depth > 16384)
            throw new IllegalArgumentException("auto_light volume exceeds 16384");
        if (maxLights < 1 || maxLights > 256 || maxScans < 1 || maxScans > 16384
                || maxTicks < 1 || maxTicks > 12000) throw new IllegalArgumentException("invalid auto_light budget");
    }
    public int volume() { return (max.getX()-min.getX()+1)*(max.getY()-min.getY()+1)*(max.getZ()-min.getZ()+1); }
    public BlockPos at(int index) {
        int width = max.getX()-min.getX()+1, depth = max.getZ()-min.getZ()+1;
        return min.offset(index % width, index / width / depth, (index / width) % depth);
    }
    public static AutoLightRequest parse(JsonObject op) {
        Set<String> allowed = Set.of("op","block_id","x1","y1","z1","x2","y2","z2","max_lights","max_scans","max_ticks");
        for (String key : op.keySet()) if (!allowed.contains(key)) throw new IllegalArgumentException("auto_light unsupported field: " + key);
        if (op.has("block_id") && (!op.get("block_id").isJsonPrimitive() || !op.get("block_id").getAsJsonPrimitive().isString()
                || !Set.of("torch","minecraft:torch").contains(op.get("block_id").getAsString())))
            throw new IllegalArgumentException("auto_light only supports minecraft:torch");
        int x1 = integer(op,"x1",null), y1 = integer(op,"y1",null), z1 = integer(op,"z1",null);
        int x2 = integer(op,"x2",null), y2 = integer(op,"y2",null), z2 = integer(op,"z2",null);
        return new AutoLightRequest(new BlockPos(Math.min(x1,x2),Math.min(y1,y2),Math.min(z1,z2)),
                new BlockPos(Math.max(x1,x2),Math.max(y1,y2),Math.max(z1,z2)),
                integer(op,"max_lights",64),integer(op,"max_scans",16384),integer(op,"max_ticks",2400));
    }
    private static int integer(JsonObject o, String key, Integer fallback) {
        if (!o.has(key)) { if (fallback != null) return fallback; throw new IllegalArgumentException(key + " required"); }
        var v = o.get(key);
        if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isNumber()) throw new IllegalArgumentException(key + " must be integer");
        try { return v.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException e) { throw new IllegalArgumentException(key + " must be an exact integer",e); }
    }
}
