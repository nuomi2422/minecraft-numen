package com.dwinovo.numen.core.task.sugarcane;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 甘蔗收割的纯状态逻辑（2026-11-07；本任务专用，不是通用执行器）。
 * 生产任务只做 MC 采样/BlockDigger 调用，选择/复核/计数/终态一律走这里；
 * 测试注入探针与回执，不启动世界。
 */
public final class SugarCaneHarvestSession {

    public enum Outcome { BROKE, PROGRESSING, NO_SHOT, OCCLUDER, CANCELLED }
    public enum State { RUNNING, SUCCESS, FAILED, TIMEOUT }

    public interface World {
        boolean loaded(BlockPos p);
        boolean isCane(BlockPos p);
    }

    private final int requested;
    private final long deadlineMs;
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private final java.util.Map<BlockPos, BlockPos> baseOf = new java.util.HashMap<>();
    private int destroyed;
    private int targetsTried;
    private String stopReason = "";
    private State state = State.RUNNING;

    public SugarCaneHarvestSession(List<BlockPos> bases, int count, int maxTargets, World world, long deadlineMs) {
        this.requested = count;
        this.deadlineMs = deadlineMs;
        int budget = count;
        int tried = 0;
        for (BlockPos base : bases == null ? List.<BlockPos>of() : bases) {
            if (budget <= 0 || tried >= maxTargets) break;
            if (base == null || !world.loaded(base)) continue;
            tried++;
            List<BlockPos> upper = new ArrayList<>();
            for (int i = 1; i <= 4; i++) {
                BlockPos p = base.above(i);
                if (!world.loaded(p) || !world.isCane(p)) break;
                upper.add(p);
            }
            int take = Math.min(budget, upper.size());
            for (int i = upper.size() - 1; i >= 0 && take > 0; i--, take--) {
                queue.addLast(upper.get(i));
                baseOf.put(upper.get(i), base);
            }
            budget -= Math.min(budget, upper.size());
        }
        this.targetsTried = tried;
        if (queue.isEmpty()) { stopReason = "no_harvestable_sugar_cane"; state = State.SUCCESS; }
    }

    public State state() { return state; }
    public int destroyed() { return destroyed; }
    public int targetsTried() { return targetsTried; }
    public String stopReason() { return stopReason; }

    /** 当前应尝试的目标；null=已在终态。 */
    public BlockPos next(long nowMs) {
        if (state != State.RUNNING) return null;
        if (nowMs > deadlineMs) { stopReason = "deadline_reached"; state = State.TIMEOUT; return null; }
        return queue.peekFirst();
    }

    public BlockPos baseOf(BlockPos target) { return baseOf.get(target); }

    /** 按端口回执推进；未知/拒绝不清障。 */
    public void accept(Outcome o, long nowMs) {
        if (state != State.RUNNING) return;
        BlockPos target = queue.peekFirst();
        switch (o) {
            case CANCELLED -> { stopReason = "cancelled"; state = State.FAILED; }
            case BROKE -> {
                destroyed++;
                queue.pollFirst();
                if (destroyed >= requested) { stopReason = "reached_count"; state = State.SUCCESS; }
                else if (nowMs > deadlineMs) { stopReason = "deadline_reached"; state = State.TIMEOUT; }
            }
            case PROGRESSING -> { /* 正常挖掘进度 */ }
            case NO_SHOT -> { stopReason = "no_shot"; state = State.FAILED; }
            case OCCLUDER -> { stopReason = "occluder_broken"; state = State.FAILED; }
        }
        if (queue.isEmpty() && state == State.RUNNING) {
            stopReason = destroyed >= requested ? "reached_count" : "scan_exhausted";
            state = State.SUCCESS;
        }
    }
}
