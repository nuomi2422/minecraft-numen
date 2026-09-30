package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.FunctionalBlockTypes;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Read-only, server-thread world evidence. Unknown/unloaded facts never pass. */
public final class RddWorldFacts {
    private RddWorldFacts() {}

    /** Caller must be on the server thread; this method never schedules work or loads chunks. */
    public static boolean matches(NumenPlayer ap, Map<String, Object> condition) {
        if (ap == null || condition == null || ap.getServer() == null
                || !ap.getServer().isSameThread()) return false;
        try {
            return switch (String.valueOf(condition.get("type"))) {
                case "advancement" -> advancement(ap, condition);
                case "entity_killed" -> killed(ap, condition);
                case "structure" -> structure(ap, condition);
                case "base" -> base(ap, condition);
                default -> false;
            };
        } catch (IllegalArgumentException exception) {
            // Malformed IDs/conditions are unproven, never inventory fallbacks.
            return false;
        }
    }

    private static ResourceLocation id(Map<String, Object> condition, String key) {
        Object raw = condition.get(key);
        if (!(raw instanceof String value) || !value.contains(":")) return null;
        return ResourceLocation.tryParse(value);
    }

    private static boolean dimension(ServerLevel level, Map<String, Object> condition) {
        if (!condition.containsKey("dimension")) return true;
        ResourceLocation wanted = id(condition, "dimension");
        return wanted != null && level.dimension().location().equals(wanted);
    }

    private static boolean advancement(NumenPlayer ap, Map<String, Object> condition) {
        ResourceLocation key = id(condition, "advancement");
        if (key == null) return false;
        var advancement = ap.getServer().getAdvancements().get(key);
        return advancement != null && ap.getAdvancements().getOrStartProgress(advancement).isDone();
    }

    private static boolean killed(NumenPlayer ap, Map<String, Object> condition) {
        ResourceLocation key = id(condition, "entity");
        Object threshold = condition.getOrDefault("minimum", 1);
        if (!(threshold instanceof Number number)) return false;
        double minimum = number.doubleValue();
        if (!Double.isFinite(minimum) || minimum < 1 || minimum > Integer.MAX_VALUE
                || minimum != Math.rint(minimum) || key == null
                || !BuiltInRegistries.ENTITY_TYPE.containsKey(key)) return false;
        // Lifetime kills credited to THIS companion, not disappearance or the owner's stats.
        return ap.getStats().getValue(Stats.ENTITY_KILLED,
                BuiltInRegistries.ENTITY_TYPE.get(key)) >= minimum;
    }

    private static boolean structure(NumenPlayer ap, Map<String, Object> condition) {
        ServerLevel level = ap.serverLevel();
        ResourceLocation key = id(condition, "structure");
        if (key == null || !dimension(level, condition)) return false;
        var registry = level.registryAccess().registryOrThrow(Registries.STRUCTURE);
        if (!registry.containsKey(key)) return false;
        var wanted = registry.get(key);
        BlockPos pos = ap.blockPosition();
        LevelChunk current = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (current == null) return false;
        if (contains(current.getStartForStructure(wanted), pos)) return true;
        var references = current.getAllReferences().get(wanted);
        if (references == null) return false;
        // StructureManager#getStructureAt may load start chunks. Resolve only already-loaded starts.
        for (long reference : references) {
            LevelChunk origin = level.getChunkSource().getChunkNow(
                    ChunkPos.getX(reference), ChunkPos.getZ(reference));
            if (origin != null && contains(origin.getStartForStructure(wanted), pos)) return true;
        }
        return false;
    }

    private static boolean contains(StructureStart start, BlockPos pos) {
        return start != null && start.isValid() && start.getBoundingBox().isInside(pos);
    }

    private static boolean base(NumenPlayer ap, Map<String, Object> condition) {
        BaseSnapshot snapshot = inspectBase(ap, condition);
        return snapshot != null && snapshot.complete();
    }

    /** A valid personal respawn bed is a reusable base asset even before every facility is complete. */
    static BaseSnapshot inspectBase(NumenPlayer ap, Map<String, Object> condition) {
        BlockPos spawn = ap.getRespawnPosition();
        // 未绑床时 getRespawnPosition() 可能返回 BlockPos.ZERO（哨兵）而非 null——
        // 必须显式挡掉，否则会拿 (0,0,0) 当"重生床"去查（语义错，且恰好在原点有床时会误判成功）。
        if (spawn == null || spawn.equals(BlockPos.ZERO)) {
            return null;
        }
        ServerLevel level = ap.getServer().getLevel(ap.getRespawnDimension());
        if (level == null || !dimension(level, condition) || !BedBlock.canSetSpawn(level)) {
            return null;
        }
        // A tiny bounded area, preflighted before any block/collision query. No chunk generation.
        for (int x = (spawn.getX() - 8) >> 4; x <= (spawn.getX() + 8) >> 4; x++) {
            for (int z = (spawn.getZ() - 8) >> 4; z <= (spawn.getZ() + 8) >> 4; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) return null;
            }
        }
        var bed = level.getBlockState(spawn);
        if (!(bed.getBlock() instanceof BedBlock)) return null;
        var facing = bed.getValue(BedBlock.FACING);
        BlockPos otherPos = spawn.relative(bed.getValue(BedBlock.PART) == BedPart.HEAD
                ? facing.getOpposite() : facing);
        var other = level.getBlockState(otherPos);
        if (!other.is(bed.getBlock()) || other.getValue(BedBlock.PART) == bed.getValue(BedBlock.PART)
                || other.getValue(BedBlock.FACING) != facing
                || BedBlock.findStandUpPosition(ap.getType(), level, spawn, facing, ap.getRespawnAngle()).isEmpty()) {
            return null;
        }
        boolean chest = false;
        boolean furnace = false;
        List<String> facilities = new ArrayList<>();
        Map<String, Integer> storedItems = new TreeMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(spawn.offset(-8, -4, -8), spawn.offset(8, 4, 8))) {
            var block = level.getBlockState(pos);
            chest |= block.is(Blocks.CHEST) || block.is(Blocks.TRAPPED_CHEST);
            furnace |= block.is(Blocks.FURNACE);
            String blockId = BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString();
            if (FunctionalBlockTypes.isTracked(blockId)) {
                facilities.add(blockId + "@" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
            }
            if (level.getBlockEntity(pos) instanceof Container container) {
                for (int slot = 0; slot < container.getContainerSize(); slot++) {
                    ItemStack stack = container.getItem(slot);
                    if (!stack.isEmpty()) {
                        storedItems.merge(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
                                stack.getCount(), Integer::sum);
                    }
                }
            }
        }
        // 2026-10-01 修（实机抓到的真死锁，用户第 5 轮实机观察）：
        // 原 complete = chest && furnace（床周围 8 格内必须同时有箱子+熔炉）。
        // 实机后果：规划器把 type=base 排成**第一个**二级 → 判据要求"基地已建成"
        // → 但造箱子/熔炉的二级排在它**后面** → base 永远不满足 → 第一个二级永远 STALLED
        // → 后面 5 个二级永远 PENDING → 整条链停摆（RL-2「依赖门不得死锁」的另一种形态：
        // 不是 WAITING 卡住，是**第一个目标自身不可达**）。
        //
        // 改法：让 complete 与本方法自己的注释一致 —— **一张已验证可用的重生床就是基地**
        //（上面已经把床本身完整校验过：双格齐全、朝向一致、旁边站得下人）。
        // 箱子/熔炉不再当硬门，但**继续扫、继续报**：缺了就写进 facilities 说明，
        // 让监测台能看出"基地还很空"，而不是只看到一个布尔值。
        //
        // 治本项另记（A2）：规划器不该把 base 排成第一个二级 —— 那是排期约束问题，
        // 要改规划器，下一批做。
        if (!chest || !furnace) {
            StringBuilder missing = new StringBuilder();
            if (!chest) missing.append("no chest within 8 blocks of the respawn bed");
            if (!furnace) {
                if (missing.length() > 0) missing.append("; ");
                missing.append("no furnace within 8 blocks of the respawn bed");
            }
            facilities.add("(missing) " + missing);
        }
        boolean complete = true;
        return new BaseSnapshot(level.dimension().location().toString(), spawn.immutable(),
                complete, List.copyOf(facilities), Map.copyOf(storedItems));
    }

    record BaseSnapshot(String dimension, BlockPos spawn, boolean complete,
                        List<String> facilities, Map<String, Integer> storedItems) {}
}
