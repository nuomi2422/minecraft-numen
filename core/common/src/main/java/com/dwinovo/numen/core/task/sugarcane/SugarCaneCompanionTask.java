package com.dwinovo.numen.core.task.sugarcane;

// Source of the "keep bottom root, harvest upper segments" idea: TartaricAcid/TouhouLittleMaid
// TaskSugarCane.java (MIT, commit 207647c85740b1d0971de6040d6dd57cea62528c). Implementation here is
// original (BlockDigger target-only), no upstream code/assets copied; see THIRD_PARTY_NOTICES.

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 有界甘蔗收割（2026-11-07，记录 008 §9.1–§9.3）。
 * 只破坏**最高的 count 格**（自上而下），保留底根；拒绝/遮挡/未加载按明确终态处理；
 * PROGRESSING 视为正常挖掘进度；不手工发掉落，拾取交原版 ITEM_PICKED_UP。
 */
public final class SugarCaneCompanionTask extends AbstractCompanionTask<SugarCaneTaskRecord> {

    private static final int MAX_COLUMN = 4;

    private final SugarCaneTaskRecord cfg;
    private final BlockDigger digger;
    private final Deque<BlockPos> queue = new ArrayDeque<>();
    private final int requested;
    private int destroyed;
    private int targetsTried;
    private String stopReason = "";

    public SugarCaneCompanionTask(com.dwinovo.numen.entity.NumenPlayer player,
                                  SugarCaneTaskRecord record, List<BlockPos> candidateBases) {
        super(player, record);
        this.cfg = record;
        this.digger = new BlockDigger(player);
        this.requested = record.count;
        int budgets = record.count;
        int tried = 0;
        for (BlockPos base : candidateBases == null ? List.<BlockPos>of() : candidateBases) {
            if (budgets <= 0 || tried >= record.maxTargets) break;
            if (base == null || !player.serverLevel().hasChunkAt(base)) continue;
            tried++;
            // 从下到上收集该株的上段，再从顶往下进队列（只取最高 budgets 格）
            List<BlockPos> upper = new ArrayList<>();
            for (int i = 1; i <= MAX_COLUMN && upper.size() <= budgets + 1; i++) {
                BlockPos p = base.above(i);
                if (!player.serverLevel().hasChunkAt(p)) break;
                if (!player.serverLevel().getBlockState(p).is(Blocks.SUGAR_CANE)) break;
                upper.add(p);
            }
            // top count cells, top-down (highest first)
            int take = Math.min(budgets, upper.size());
            for (int i = upper.size() - 1; i >= 0 && take > 0; i--, take--) {
                queue.addLast(upper.get(i));
            }
            budgets -= Math.min(budgets, upper.size());
        }
        this.targetsTried = tried;
        if (queue.isEmpty()) stopReason = "no_harvestable_sugar_cane";
    }

    private static List<Integer> ys(List<BlockPos> ps) {
        List<Integer> out = new ArrayList<>(ps.size());
        for (BlockPos p : ps) out.add(p.getY());
        return out;
    }

    @Override
    protected TaskState onTick() {
        SugarCaneTaskRecord r = cfg;
        if (System.currentTimeMillis() > r.deadlineMs) {
            stopReason = "deadline_reached";
            return TaskState.TIMEOUT;
        }
        if (queue.isEmpty()) {
            if (stopReason.isBlank()) stopReason = destroyed >= requested ? "reached_count" : "scan_exhausted";
            return TaskState.SUCCESS;
        }
        BlockPos target = queue.peekFirst();
        BlockDigger.DigResult res = digger.digTargetStep(target);
        switch (res) {
            case BROKE_TARGET -> {
                destroyed++;
                queue.pollFirst();
                return destroyed >= requested ? finish("reached_count") : TaskState.RUNNING;
            }
            case PROGRESSING -> {
                return TaskState.RUNNING;               // 正常挖掘进度，不是权限失败
            }
            case NO_SHOT, BROKE_OCCLUDER -> {
                stopReason = res == BlockDigger.DigResult.NO_SHOT ? "no_shot" : "occluder_broken";
                fail("sugar cane target unavailable (" + res + ")", FailureType.TARGET_LOST);
                return TaskState.FAILED;
            }
            default -> {
                stopReason = "unknown_dig_result";
                fail("unexpected dig result " + res, FailureType.INTERNAL);
                return TaskState.FAILED;
            }
        }
    }

    private TaskState finish(String why) {
        stopReason = why;
        return TaskState.SUCCESS;
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requested", requested);
        m.put("destroyedBlocks", destroyed);
        m.put("targetsTried", targetsTried);
        m.put("stopReason", stopReason);
        return m;
    }

    @Override
    protected String successMessage() {
        return "sugar cane: destroyed " + destroyed + "/" + requested + " upper blocks ("
                + (stopReason.isBlank() ? "done" : stopReason) + ")";
    }
}
