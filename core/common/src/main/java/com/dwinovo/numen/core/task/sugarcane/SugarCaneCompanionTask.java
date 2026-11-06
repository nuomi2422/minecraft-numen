package com.dwinovo.numen.core.task.sugarcane;

// Source of the "keep bottom root, harvest upper segments" idea: TartaricAcid/TouhouLittleMaid
// TaskSugarCane.java (MIT, commit 207647c85740b1d0971de6040d6dd57cea62528c). Implementation is
// original (BlockDigger target-only); see THIRD_PARTY_NOTICES.

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 有界甘蔗收割（2026-11-07，记录 008 §9.1–§9.3）。选择/复核/计数/终态全部委托
 * {@link SugarCaneHarvestSession}；本类只做 MC 采样与 BlockDigger 调用。
 */
public final class SugarCaneCompanionTask extends AbstractCompanionTask<SugarCaneTaskRecord> {

    private final SugarCaneTaskRecord cfg;
    private final NumenPlayer owner;
    private final SugarCaneHarvestSession session;
    private final BlockDigger digger;
    private final SugarCaneHarvestSession.World worldProbe;
    private final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> startDimension;
    private final net.minecraft.server.level.ServerLevel startLevel;

    public SugarCaneCompanionTask(NumenPlayer player, SugarCaneTaskRecord record) {
        this(player, record, scanBases(player, record.radius));
    }

    /** 有界局部扫描：只找“底根”（下方不是甘蔗）的甘蔗列；不加载未知区块。 */
    private static List<BlockPos> scanBases(NumenPlayer player, int radius) {
        var level = player.serverLevel();
        BlockPos center = player.blockPosition();
        java.util.ArrayList<BlockPos> out = new java.util.ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 4; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (!level.hasChunkAt(pos)) continue;
                    if (!level.getBlockState(pos).is(Blocks.SUGAR_CANE)) continue;
                    if (level.getBlockState(pos.below()).is(Blocks.SUGAR_CANE)) continue; // 只要底根
                    out.add(pos);
                }
            }
        }
        return out;
    }

    public SugarCaneCompanionTask(NumenPlayer player, SugarCaneTaskRecord record, List<BlockPos> candidateBases) {
        super(player, record);
        this.cfg = record;
        this.owner = player;
        this.digger = new BlockDigger(player);
        var level = player.serverLevel();
        SugarCaneHarvestSession.World world = new SugarCaneHarvestSession.World() {
            @Override public boolean loaded(BlockPos p) { return level.hasChunkAt(p); }
            @Override public boolean isCane(BlockPos p) { return level.getBlockState(p).is(Blocks.SUGAR_CANE); }
        };
        this.worldProbe = world;
        this.startDimension = player.serverLevel().dimension();
        this.startLevel = player.serverLevel();
        this.session = new SugarCaneHarvestSession(candidateBases, record.count, record.maxTargets,
                world, record.deadlineMs);
    }

    @Override
    protected TaskState onTick() {
        long now = System.currentTimeMillis();
        if (owner.serverLevel() != startLevel || !owner.serverLevel().dimension().equals(startDimension)) {
            fail("dimension changed since task start", FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        BlockPos target = session.next(now);
        if (target == null) return terminal();
        String why = session.precheck(target, worldProbe);
        if (why != null) {
            fail("sugar cane precheck failed: " + why, FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        BlockDigger.DigResult r = digger.digTargetStep(target);
        switch (r) {
            case BROKE_TARGET -> session.accept(SugarCaneHarvestSession.Outcome.BROKE, System.currentTimeMillis());
            case PROGRESSING -> session.accept(SugarCaneHarvestSession.Outcome.PROGRESSING, now);
            case NO_SHOT -> session.accept(SugarCaneHarvestSession.Outcome.NO_SHOT, now);
            case BROKE_OCCLUDER -> session.accept(SugarCaneHarvestSession.Outcome.OCCLUDER, now);
            default -> { fail("unexpected dig result " + r, FailureType.INTERNAL); return TaskState.FAILED; }
        }
        return terminal();
    }

    private TaskState terminal() {
        return switch (session.state()) {
            case RUNNING -> TaskState.RUNNING;
            case SUCCESS -> TaskState.SUCCESS;
            case TIMEOUT -> { fail("sugar cane deadline reached", FailureType.TIMED_OUT); yield TaskState.FAILED; }
            case FAILED -> { fail("sugar cane stopped: " + session.stopReason(), FailureType.TARGET_LOST);
                yield TaskState.FAILED; }
        };
    }

    @Override
    protected void cleanup() {
        digger.cancel();   // 复用 BlockDigger 既有 ABORT_DESTROY_BLOCK + 清裂纹入口
        super.cleanup();
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requested", cfg.count);
        m.put("destroyedBlocks", session.destroyed());
        m.put("targetsTried", session.targetsTried());
        m.put("stopReason", session.stopReason());
        return m;
    }

    @Override
    protected String successMessage() {
        return "sugar cane: destroyed " + session.destroyed() + "/" + cfg.count + " upper blocks ("
                + (session.stopReason().isBlank() ? "done" : session.stopReason()) + ")";
    }
}
