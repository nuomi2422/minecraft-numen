package com.dwinovo.numen.plugins.worldstate.probe;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;

/**
 * 探针执行的只读上下文。由 {@code WorldProbeTool} 在服务端线程构造。
 *
 * <p>{@code level} 可能为 null（维度未加载/名字非法）；需要世界的探针必须自己
 * 返回 {@code DIMENSION_MISSING}，不能拿主世界顶替。
 */
public record ProbeContext(MinecraftServer server,
                           ServerLevel level,
                           ServerPlayer target,
                           String worldId,
                           String dimension,
                           int x,
                           int y,
                           int z,
                           int radius,
                           String category,
                           String type,
                           List<String> gameruleKeys) {

    public int radiusOrDefault(int fallback) {
        return radius <= 0 ? fallback : radius;
    }
}
