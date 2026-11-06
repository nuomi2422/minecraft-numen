package com.dwinovo.numen.core.task.light;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 当前暗处照明纯规划（2026-11-07，上游 MaidTorchMoveTask 口径 getMaxLocalRawBrightness<9）。
 * 只做站立火把；放置交给 build 的 useItemOn。不宣称永久防夜刷怪。
 */
public final class LightDeficitPlanner {
    public static final int DARK_THRESHOLD = 9;

    public interface World {
        boolean loaded(BlockPos p);
        int maxLocalRawBrightness(BlockPos p);
        boolean replaceable(BlockPos p);
        boolean canSupportStandingTorch(BlockPos p);
        boolean alreadyTorch(BlockPos p);
    }

    public record Result(List<BlockPos> lights, String stopReason) {}

    public static Result plan(List<BlockPos> candidates, int maxLights, World world) {
        if (candidates == null || world == null) return new Result(List.of(), "no_candidates");
        List<BlockPos> out = new ArrayList<>();
        for (BlockPos p : candidates) {
            if (out.size() >= maxLights) return new Result(List.copyOf(out), "reached_max_lights");
            if (p == null || !world.loaded(p)) continue;
            if (world.alreadyTorch(p)) continue;
            if (!world.replaceable(p)) continue;
            if (!world.canSupportStandingTorch(p)) continue;
            if (world.maxLocalRawBrightness(p) >= DARK_THRESHOLD) continue;
            out.add(p);
        }
        return new Result(List.copyOf(out), out.isEmpty() ? "no_dark_candidates" : "planned");
    }
}
