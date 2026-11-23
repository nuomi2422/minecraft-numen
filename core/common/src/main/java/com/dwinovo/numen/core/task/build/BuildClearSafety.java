package com.dwinovo.numen.core.task.build;

import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.function.LongPredicate;

/**
 * 清格准入:这一刀挖下去会不会放出上面的落沙/液体,或者把身体脚下的承重面挖穿。
 *
 * <p>常规施工里真会挖掉世界方块的是 {@code clear()}(收尾拆脚手架另有 destroyBlock),
 * 而它走的是 {@code setBlock(AIR, PLACE_FLAGS)}:不发邻居更新,落沙不会当场掉,危险
 * 变成潜伏——等下一次邻居变化才整柱塌下来。寻路层早有一整套判据(落沙/流体/未加载),
 * 但它们只被 A* 的挖掘代价消费,建造侧一个都没有接。
 *
 * <p>本类只做判定,不写世界、不摸任务状态。两条硬律(正上方落沙、正上方液体)与身体
 * 无关;其余只在“这一格确实在身体足迹下方”时才核下落路径。普通实地逐层下挖、侧挖
 * 一律放行——不做“禁挖脚下”的一刀切。
 */
final class BuildClearSafety {

    /**
     * 与寻路 {@code MovementDescend} 的 {@code -3} 同一条原版界限:下落距离不超过 3 格
     * 才不计摔伤。这里取保守侧,恰好 3 格放行,4 格起拒绝。
     */
    static final int SAFE_FALL_BLOCKS = 3;

    /** 从脚底多往下看一格就能判出“超过 3 格”,不必扫到基岩。 */
    private static final int SCAN_DROP = SAFE_FALL_BLOCKS + 1;

    private static final double EPS = 1.0E-6;

    /**
     * 身体脚下真实接触到的承重面:格、顶面高度(绝对 y)、是否液面、是否危险。
     * 楼梯/栅栏/多碰撞箱的最高面未必在脚下,所以顶面只按与身体 XZ 足迹真正相交的
     * AABB 算;不相交的形状不截断向下扫描。
     */
    private record Surface(BlockPos pos, double top, boolean liquid, boolean hazard) { }

    /** 一列的扫描结果;unknown = 还没找到落面就读到了未加载区块。 */
    private record Scan(Surface surface, boolean unknown) { }

    enum Admission { ALLOW, REFUSE, UNKNOWN }

    record Verdict(Admission admission, String reason) {
        boolean allowed() {
            return admission == Admission.ALLOW;
        }

        static Verdict allow() {
            return new Verdict(Admission.ALLOW, "");
        }

        static Verdict refuse(String reason) {
            return new Verdict(Admission.REFUSE, reason);
        }

        static Verdict unknown(String reason) {
            return new Verdict(Admission.UNKNOWN, reason);
        }
    }

    private BuildClearSafety() {
    }

    /**
     * @param level     只读世界(生产传真实 Level,读前先过 loaded,绝不触发同步生成)
     * @param loaded    chunk 加载谓词;未加载一律按 UNKNOWN,不当空气
     * @param pos       即将被 clear 的格
     * @param body      身体 AABB(足迹可能跨两列,半格/台阶也要算)
     * @param willClear 本批还会真实执行清除的格;只装真清除,不装保留/已匹配格
     */
    static Verdict check(BlockGetter level, ChunkLoadedTest loaded, BlockPos pos,
                         AABB body, LongPredicate willClear) {
        if (!loaded.isLoaded(pos.getX(), pos.getZ())) {
            return Verdict.unknown("target chunk not loaded");
        }
        // 正上方:落沙与液体与身体无关,任何清除都先过这两条。
        BlockPos abovePos = pos.above();
        if (!loaded.isLoaded(abovePos.getX(), abovePos.getZ())) {
            return Verdict.unknown("cell above not loaded");
        }
        if (BlockHelper.breakReleasesFallingBlock(level, pos)) {
            return Verdict.refuse("breaking " + pos.toShortString()
                    + " releases a falling block sitting above it");
        }
        if (!level.getBlockState(abovePos).getFluidState().isEmpty()) {
            return Verdict.refuse("liquid directly above " + pos.toShortString()
                    + " would pour into the cleared cell");
        }

        int feetY = (int) Math.floor(body.minY + 1.0E-4);
        int x0 = (int) Math.floor(body.minX);
        int x1 = (int) Math.floor(body.maxX - EPS);
        int z0 = (int) Math.floor(body.minZ);
        int z1 = (int) Math.floor(body.maxZ - EPS);
        boolean underFootprint = pos.getX() >= x0 && pos.getX() <= x1
                && pos.getZ() >= z0 && pos.getZ() <= z1
                && pos.getY() <= feetY;
        if (!underFootprint) {
            return Verdict.allow();   // 侧挖或身体上方:不构成“挖掉承重面”
        }

        // 现在脚下踩的是什么。可能跨两列不同高度(台阶),取足迹内最高承重面。
        double standingTop = Double.NEGATIVE_INFINITY;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                Scan scan = firstSurface(level, loaded, x, feetY, z, 1, body, key -> false);
                if (scan.unknown()) {
                    return Verdict.unknown("support column at " + x + "," + z + " not loaded");
                }
                Surface surface = scan.surface();
                if (surface != null && Math.abs(surface.top() - body.minY) <= 0.6) {
                    standingTop = Math.max(standingTop, surface.top());
                }
            }
        }

        // 本批还会清掉的格按空气算:不能把“下一步就要挖掉的地面”当安全底。
        LongPredicate removed = key -> key == pos.asLong() || willClear.test(key);
        Surface landing = null;
        boolean unknownBelow = false;
        for (int x = x0; x <= x1; x++) {
            for (int z = z0; z <= z1; z++) {
                Scan scan = firstSurface(level, loaded, x, feetY, z, SCAN_DROP, body, removed);
                if (scan.unknown()) {
                    unknownBelow = true;
                    continue;
                }
                Surface surface = scan.surface();
                if (surface != null && (landing == null || surface.top() > landing.top())) {
                    landing = surface;
                }
            }
        }
        if (unknownBelow) {
            return Verdict.unknown("drop path below " + pos.toShortString() + " not loaded");
        }
        if (landing == null) {
            return Verdict.refuse("no floor within " + SCAN_DROP + " blocks below "
                    + pos.toShortString());
        }
        // 贴地时承重面没变就是侧向/下层清理,放行;身体正在空中时不能按“没贴着”自动安全,
        // 仍按真实落面核一次(下面可能是刚要落脚的底)。
        if (standingTop != Double.NEGATIVE_INFINITY && landing.top() >= standingTop - EPS) {
            return Verdict.allow();
        }
        double drop = body.minY - landing.top();
        if (drop > SAFE_FALL_BLOCKS + EPS) {
            return Verdict.refuse(String.format(
                    "dropping %.1f blocks (> %d) from %s", drop, SAFE_FALL_BLOCKS,
                    pos.toShortString()));
        }
        if (landing.liquid()) {
            return Verdict.refuse("landing in liquid below " + pos.toShortString());
        }
        if (landing.hazard()) {
            return Verdict.refuse("landing on a hazard block at "
                    + landing.pos().toShortString());
        }
        return Verdict.allow();
    }

    /**
     * 某一列自脚底格向下找第一个<b>真正压在身体足迹里</b>的承重面:
     * 碰撞形状非空且至少一个 AABB 与身体 XZ 相交才停;形状全在脚外、空气、无碰撞
     * 装饰都继续向下。流体格算液面(停),无碰撞的危险格算危险面(停)。
     */
    private static Scan firstSurface(BlockGetter level, ChunkLoadedTest loaded,
                                     int x, int feetY, int z, int maxDrop,
                                     AABB body, LongPredicate removed) {
        for (int dy = 0; dy <= maxDrop; dy++) {
            BlockPos p = new BlockPos(x, feetY - dy, z);
            if (!loaded.isLoaded(p.getX(), p.getZ())) {
                return new Scan(null, true);
            }
            if (removed.test(p.asLong())) {
                continue;
            }
            BlockState state = level.getBlockState(p);
            if (state.isAir()) {
                continue;
            }
            VoxelShape shape = state.getCollisionShape(level, p);
            if (!shape.isEmpty()) {
                double top = topUnderBody(p, body, shape);
                if (top == Double.NEGATIVE_INFINITY) {
                    continue;   // 最高面不在脚下:不截断,继续看下一层
                }
                return new Scan(new Surface(p, top, false, BlockHelper.isHazard(level, p)), false);
            }
            if (!state.getFluidState().isEmpty()) {
                return new Scan(new Surface(p, p.getY(), true, false), false);
            }
            if (BlockHelper.isHazard(level, p)) {
                return new Scan(new Surface(p, p.getY(), false, true), false);
            }
        }
        return new Scan(null, false);
    }

    /** 与身体 XZ 足迹真正相交的形状 AABB 的最高顶面;没有则负无穷。 */
    private static double topUnderBody(BlockPos p, AABB body, VoxelShape shape) {
        double top = Double.NEGATIVE_INFINITY;
        for (AABB part : shape.toAabbs()) {
            double partX0 = p.getX() + part.minX;
            double partX1 = p.getX() + part.maxX;
            double partZ0 = p.getZ() + part.minZ;
            double partZ1 = p.getZ() + part.maxZ;
            if (partX1 <= body.minX + EPS || partX0 >= body.maxX - EPS) {
                continue;
            }
            if (partZ1 <= body.minZ + EPS || partZ0 >= body.maxZ - EPS) {
                continue;
            }
            top = Math.max(top, p.getY() + part.maxY);
        }
        return top;
    }
}
