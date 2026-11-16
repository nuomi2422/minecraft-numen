package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.agent.FunctionalBlockTypes;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.core.WorldFactConditions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import com.dwinovo.numen.plugins.rdd.RddWorldFacts.BaseSnapshot;

import static com.dwinovo.numen.plugins.rdd.RddWorldFacts.dimension;
import static com.dwinovo.numen.plugins.rdd.RddWorldFacts.BASELOG;

/**
 * Current server-thread base/bed-area sampling, not a home registry or an asset cache.
 * Keeps the existing direct-world path, loaded-area gates and scan order intact.
 * HomeFacts continues to project retained observations; no second source of stored truth.
 */
final class RddBaseFacts {
    private RddBaseFacts() {}

    static BaseSnapshot inspectBase(NumenPlayer ap, Map<String, Object> condition) {
        BlockPos spawn = ap.getRespawnPosition();
        BASELOG.info("[rdd-base] 检查：spawn={} dim={} 当前维度={}", spawn,
                ap.getRespawnDimension(), ap.level().dimension().location());
        // 未绑床时 getRespawnPosition() 可能返回 BlockPos.ZERO（哨兵）而非 null——
        // 必须显式挡掉，否则会拿 (0,0,0) 当"重生床"去查（语义错，且恰好在原点有床时会误判成功）。
        if (spawn == null || spawn.equals(BlockPos.ZERO)) {
            BASELOG.info("[rdd-base] 失败：未绑床（spawn=null/ZERO）");
            return null;
        }
        ServerLevel level = ap.getServer().getLevel(ap.getRespawnDimension());
        if (level == null || !dimension(level, condition) || !BedBlock.canSetSpawn(level)) {
            BASELOG.info("[rdd-base] 失败：level={} dimensionOk={} canSetSpawn={}", level,
                    level != null && dimension(level, condition), level != null && BedBlock.canSetSpawn(level));
            return null;
        }
        // A tiny bounded area, preflighted before any block/collision query. No chunk generation.
        for (int x = (spawn.getX() - 8) >> 4; x <= (spawn.getX() + 8) >> 4; x++) {
            for (int z = (spawn.getZ() - 8) >> 4; z <= (spawn.getZ() + 8) >> 4; z++) {
                if (level.getChunkSource().getChunkNow(x, z) == null) return null;
            }
        }
        var bed = level.getBlockState(spawn);
        if (!(bed.getBlock() instanceof BedBlock)) {
            BASELOG.info("[rdd-base] 失败：spawn 处不是床，实际={}", bed.getBlock());
            return null;
        }
        var facing = bed.getValue(BedBlock.FACING);
        BlockPos otherPos = spawn.relative(bed.getValue(BedBlock.PART) == BedPart.HEAD
                ? facing.getOpposite() : facing);
        var other = level.getBlockState(otherPos);
        if (!other.is(bed.getBlock()) || other.getValue(BedBlock.PART) == bed.getValue(BedBlock.PART)
                || other.getValue(BedBlock.FACING) != facing) {
            return null;
        }
        // 2026-10-25（主人）：**点一下床就算安家**。这里原来还要求
        //   BedBlock.findStandUpPosition(...).isEmpty() == false（床旁边得有地方站起来），
        //   斜坡/草丛边上经常判空 ⇒ 重生点明明绑好了，`base` 事实却是 false，
        //   于是 AI 以为自己没有基地、反复找地方重绑。站不站得起来只影响"这一觉"，不影响"家在哪"。
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
        //（上面校验床双格齐全、朝向一致；不再校验站位，见 2026-10-25 说明）。
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

    // ──────────────────────────────────────────────────────────────────────
    // D 组：基地周边判据。stocked/lit 要求床锚；shelter 无床时按原行为锚定自身。
    // ──────────────────────────────────────────────────────────────────────

    /** 床坐标；未绑床/床被拆返回 null（三个分级的公共入口）。 */
    private static BlockPos bedAnchor(NumenPlayer ap) {
        BlockPos spawn = ap.getRespawnPosition();
        if (spawn == null || spawn.equals(BlockPos.ZERO)) return null;
        if (!(ap.level() instanceof ServerLevel level)) return null;
        return level.getBlockState(spawn).getBlock() instanceof BedBlock ? spawn.immutable() : null;
    }

    /** D 组半径：床周围水平多少格（默认 8，与 base 的扫描口径一致）。 */
    private static int baseRadius(Map<String, Object> condition) {
        return WorldFactConditions.radiusOf(condition, WorldFactConditions.DEFAULT_BLOCK_RADIUS);
    }

    /** 三个 D 组判据共用的床心扫描：水平 dx²+dz²≤r²，dy 含端点，未加载区块跳过；
     *  stopWhen 返回 true = 提前停（stocked/lit 达标即停；shelter 恒 false 全扫）。 */
    private static void scanAround(ServerLevel level, BlockPos center, int radius, int dyMin, int dyMax,
                                   Predicate<BlockPos> stopWhen) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int dy = dyMin; dy <= dyMax; dy++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.hasChunkAt(cursor)) continue;
                    if (stopWhen.test(cursor)) return;
                }
            }
        }
    }

    /**
     * shelter：床周围半径 R 内"围合"达标。
     * 判据（刻意可解释、可懒造）：墙块（solid 且不是床/门/火把这类功能性方块）≥ minimum（默认 20），
     * 且床头顶 ≤2 格内有顶盖（solid 方块）——"有个能挡雨睡觉的角落"，不要求像人盖的房子。
     */
    static boolean shelterAround(NumenPlayer ap, Map<String, Object> condition) {
        BlockPos bed = bedAnchor(ap);
        // ★ 2026-10-28 实机：**下界/末地放不了床** → bedAnchor 恒为 null → shelter 永远 false。
        //   而链在下界给她派了「建个庇护所（shelter ≥20）」，于是失败→卡死。
        //   判据本意是"你身边有没有挡得住风雨的角落"；床只是**有床时更准的锚点**，
        //   没床时就锚在**她自己脚下** —— 语义不变，但可达成了。
        if (bed == null) {
            bed = ap.blockPosition().immutable();
        }
        ServerLevel level = ap.serverLevel();
        int radius = baseRadius(condition);
        int minimum = WorldFactConditions.minimumOf(condition, 20);
        int[] walls = {0};
        scanAround(level, bed, radius, 0, 4, pos -> {
            if (isWallLike(level.getBlockState(pos))) walls[0]++;
            return false;
        });
        if (walls[0] < minimum) return false;
        // 顶盖：床正上方 1~2 格内有 solid
        for (int dy = 1; dy <= 2; dy++) {
            var above = level.getBlockState(bed.above(dy));
            if (above.isSolid()) return true;
        }
        return false;
    }

    /** 墙块：solid 且不是床/门/容器/光源（那些是"设施"，不算墙）。 */
    private static boolean isWallLike(BlockState state) {
        return state.isSolid()
                && !(state.getBlock() instanceof BedBlock)
                && !state.is(Blocks.CHEST) && !state.is(Blocks.TRAPPED_CHEST)
                && !state.is(Blocks.FURNACE) && !state.is(Blocks.CRAFTING_TABLE)
                && !state.is(Blocks.TORCH) && !state.is(Blocks.WALL_TORCH)
                && !state.is(Blocks.LANTERN) && !state.is(Blocks.SOUL_LANTERN)
                && !(state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock);
    }

    /**
     * stocked：床半径 R 内所有容器里的物品**总件数** ≥ minimum（默认 10）。
     * 判"真的放了东西"，不是"附近有个空箱子"——AI 把装备存进去才算数。
     */
    static boolean stockedAround(NumenPlayer ap, Map<String, Object> condition) {
        BlockPos bed = bedAnchor(ap);
        if (bed == null) return false;
        ServerLevel level = ap.serverLevel();
        int radius = baseRadius(condition);
        int minimum = WorldFactConditions.minimumOf(condition, 10);
        int[] total = {0};
        scanAround(level, bed, radius, -2, 3, pos -> {
            if (!(level.getBlockEntity(pos) instanceof Container container)) return false;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty()) total[0] += stack.getCount();
            }
            return total[0] >= minimum;
        });
        return total[0] >= minimum;
    }

    /** lit：床半径 R 内八种明枚举光源 ≥ minimum（默认 4），不依赖虚构 tag。 */
    static boolean litAround(NumenPlayer ap, Map<String, Object> condition) {
        BlockPos bed = bedAnchor(ap);
        if (bed == null) return false;
        ServerLevel level = ap.serverLevel();
        int radius = baseRadius(condition);
        int minimum = WorldFactConditions.minimumOf(condition, 4);
        // 光源用明枚举而不是 tag：原版没有现成的"光源" tag，编一个 tag 反而会让规划器
        // 生成永远解析不了的东西（RL-18 同族）。这八个是原版可放置光源的全集。
        Predicate<BlockState> isLight = state -> state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)
                || state.is(Blocks.LANTERN) || state.is(Blocks.SOUL_LANTERN)
                || state.is(Blocks.GLOWSTONE) || state.is(Blocks.SEA_LANTERN)
                || state.is(Blocks.JACK_O_LANTERN) || state.is(Blocks.SHROOMLIGHT);
        int[] found = {0};
        scanAround(level, bed, radius, -2, 3, pos -> {
            if (isLight.test(level.getBlockState(pos))) found[0]++;
            return found[0] >= minimum;
        });
        return found[0] >= minimum;
    }
}
