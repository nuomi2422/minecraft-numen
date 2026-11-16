package com.dwinovo.numen.core.task.locate;

import com.dwinovo.numen.core.task.CompassUtil;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;

/**
 * 2026-11-17 #21：locate_biome / locate_structure 的结果形状只有首个标签键不同，
 * 其余 found/x/y/z/direction/horizontal_distance 完全一致。收敛到一处，任务侧只传标签键值。
 * 调用方仍是各自 resultData() 覆盖，结果 map 的键与值不变。
 */
final class LocateResultData {
    private LocateResultData() {}

    static Map<String, Object> of(String labelKey, String labelValue, BlockPos best, BlockPos me) {
        Map<String, Object> data = new HashMap<>();
        data.put(labelKey, labelValue);
        if (best != null) {
            int dx = best.getX() - me.getX();
            int dz = best.getZ() - me.getZ();
            int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
            data.put("found", true);
            data.put("x", best.getX());
            data.put("y", best.getY());
            data.put("z", best.getZ());
            data.put("direction", CompassUtil.compass(dx, dz));
            data.put("horizontal_distance", dist);
        } else {
            data.put("found", false);
        }
        return data;
    }
}