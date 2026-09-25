package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 床边复活锚点（从 {@code RddPlugin} 拆出的独立职责，2026-09-26 复杂度拆解）。
 *
 * <p>职责：死亡时记录同伴"自己绑的"床位；SPAWN 时把同伴 TP 到床旁安全落点。
 *
 * <p>基准红线：**RL-7 床边复活**（绑床 → 死亡 → 复活在床旁）——本类是其唯一实现，
 * 拆出时必须保持行为逐字不变：内存锚点优先、重启回落读 live {@code getRespawnPosition()}、
 * ZERO 哨兵挡掉、{@code BedBlock.findStandUpPosition} 落点。
 */
final class RddBedAnchor {

    private RddBedAnchor() {}

    private static final Logger LOG = LoggerFactory.getLogger(RddBedAnchor.class);

    /** 同伴自绑床位复活锚点：companionId → 同伴自己的 respawn 床位（成功 sleep/右键绑床写入）。 */
    private static final Map<UUID, BlockPos> BED_RESPAWN_PREFERENCE = new ConcurrentHashMap<>();

    /**
     * 死亡时记录同伴"自己绑的"床：读同伴自己的 respawn 床位——成功 sleep 时原版
     * {@code ServerPlayer.startSleepInBed}（或我们 interact_at 的 setRespawnPosition）会设它。
     * 仅当该床位与同伴当前维度一致、且该处确为床时才记录（避免跨维度误 TP）。
     */
    static void record(NumenPlayer body, UUID companionId) {
        try {
            BlockPos bed = body.getRespawnPosition();
            if (bed == null || bed.equals(BlockPos.ZERO)) return;
            var bedDimension = body.getRespawnDimension();
            ServerLevel level = body.serverLevel();
            if (bedDimension == null || level == null || !level.dimension().equals(bedDimension)) return;
            if (!(level.getBlockState(bed).getBlock() instanceof BedBlock)) return;
            BED_RESPAWN_PREFERENCE.put(companionId, bed.immutable());
            LOG.info("[rdd] 记录 {} 自绑床位: {} @ {}", companionId, bed, bedDimension.location());
        } catch (RuntimeException ex) {
            LOG.warn("[rdd] 记录自绑床位失败 {}: {}", companionId, ex.toString());
        }
    }

    /**
     * SPAWN 时 TP 到床旁：内存锚点优先（死亡时记的）；重启后内存已清 → 回落读同伴**自己的**
     * live respawn 点位（原版持久化在 .dat，重启不丢）。只在目标是床块时才 TP。
     */
    static void applyOnSpawn(NumenPlayer body) {
        UUID uuid = body.getUUID();
        if (uuid == null) {
            LOG.warn("[rdd] SPAWN event missing companion UUID");
            return;
        }
        BlockPos bedPos = BED_RESPAWN_PREFERENCE.remove(uuid);
        if (bedPos == null || bedPos.equals(BlockPos.ZERO)) {
            try {
                BlockPos live = body.getRespawnPosition();
                if (live != null && !live.equals(BlockPos.ZERO)) {
                    bedPos = live;
                }
            } catch (RuntimeException ignore) {
                // 读不到就不 TP
            }
        }
        if (bedPos == null) return;
        try {
            ServerLevel level = body.serverLevel();
            if (level == null || !(level.getBlockState(bedPos).getBlock() instanceof BedBlock)) return;
            Vec3 stand = bedStandPos(level, bedPos, body);
            body.moveTo(stand.x, stand.y, stand.z, body.getYRot(), body.getXRot());
            LOG.info("[rdd] {} 复活后 TP 到床旁: {}", uuid, bedPos);
        } catch (RuntimeException e) {
            LOG.warn("[rdd] 床边复活 TP 失败 {}: {}", uuid, e.toString());
        }
    }

    /** 床边安全落点：优先 BedBlock 标准站立位，异常/无解时回落床心（X/Z 偏移 0.5）。 */
    private static Vec3 bedStandPos(ServerLevel level, BlockPos bed, NumenPlayer body) {
        try {
            BlockState state = level.getBlockState(bed);
            if (state.getBlock() instanceof BedBlock) {
                var facing = state.getValue(BedBlock.FACING);
                var stand = BedBlock.findStandUpPosition(body.getType(), level, bed, facing, body.getRespawnAngle());
                if (stand.isPresent()) return stand.get();
            }
        } catch (RuntimeException ignore) {
            // 落回下面的兜底坐标
        }
        return new Vec3(bed.getX() + 0.5, bed.getY(), bed.getZ() + 0.5);
    }
}
