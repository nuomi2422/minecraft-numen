package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * 摔落自救:掉得够快就在落点铺一摊水(或者垫一块软方块),落进去之后<b>把水收回来</b>。
 *
 * <h2>两个阶段,一条链</h2>
 * <pre>
 * 正在摔        → 瞄准落点、够得着就倒水
 * 落进自己的水  → 换空桶、瞄住那格、收回来
 * </pre>
 * 收水必须留在同一条链里:桶是消耗品,放完不收就只能救一次。而它压过一切(这条链在最前),
 * 所以窗口卡死在 {@link #RECLAIM_TICKS} ——装桶、看向、点右键统共三五刻,到点无条件放手。
 *
 * <h2>两个窗口:让身体 ≠ 销账(这一版修的)</h2>
 * 快窗口到点只允许"把身体还回去",<b>不允许把账销掉</b>:那摊水还在世界里,收回来才是对的。
 * 所以窗口到期时水若还在,转入一个低优先级的{@link #CLEANUP_BUDGET_TICKS} 清理窗口继续收;
 * 清理窗口也到期才允许放手,并且<b>必须留一句日记</b>——世界少了一格水,得有人知道。
 *
 * <p>同样地,被更高优先级的反射抢占({@link #stop()})时<b>不再无条件销账</b>:以前这里
 * 一句 {@code placed = null} 就是"落地水不回收"的成因——抢占一次,水既没收回也没记录,
 * 永久留在世界里。判据见 {@link TempResourceRules}(可无头单测)。
 *
 * <h2>软方块不自动拆</h2>
 * 垫在落点的干草/史莱姆块留在世界里,但<b>不自动拆</b>:她可能正站在上面,为了收尾把救命
 * 物抽走比留着更危险。只记一笔位置与用途,交给上层决定。
 *
 * <h2>够不够得着由桶自己说</h2>
 * 放水和收水都先按<b>原版 {@code BucketItem} 那条射线</b>问一次:从眼睛沿视线打
 * {@code blockInteractionRange}。够得着才点,而且必须真的瞄在那一格上 —— 判据与真正
 * 执行的是同一条射线,不会出现"以为够得着、点下去什么也没发生"。
 *
 * <h2>探落点用五条射线</h2>
 * 她的碰撞箱宽 0.6,单一条竖直射线会从格缝里漏下去。中心加四角各打一条、取最近的那个落点,
 * 边缘擦着掉也找得到。横向漂移不用外推:每刻重探一次,而临放水时她离地只剩一两刻,
 * 那点位移还在碰撞箱以内。
 */
public final class MLGChain implements Task, com.dwinovo.numen.task.reflex.Reflex {

    /** 向下探地的最大深度。 */
    private static final double PROBE_DEPTH = 40.0;

    /**
     * 放完水之后收桶的窗口(刻)。这条链压过自卫、脱困等一切,所以窗口只够动作本身:
     * 装桶、转头、点一下右键。到点就<b>让出身体</b>,免得水流走了她还锁着身体不放。
     */
    private static final int RECLAIM_TICKS = 20;

    /**
     * 清理窗口(刻):快窗口到期、或被抢占之后,水还在世界里就靠它把账销掉。
     * 比快窗口宽裕得多(它不再和别的反射抢第一顺位),但仍然有上限——一条反射
     * 不许因为一摊水永久占住身体。
     */
    private static final int CLEANUP_BUDGET_TICKS = 200;

    /** 刚倒下去的那摊水在哪一格;没放过水则 null。 */
    private BlockPos placed;
    /** 收桶快窗口的倒计时。 */
    private int reclaimTicks;
    /** 清理窗口的倒计时(快窗口到期或被抢占后接管)。 */
    private int cleanupTicks;

    /** One diary line per fall episode (reset when the save ends). */
    private boolean notedThisFall;
    /** 收不回来时只报一次,免得每刻刷屏。 */
    private boolean abandonNoted;

    public MLGChain() {
    }

    @Override
    public boolean canRun(NumenPlayer companion) {
        // A switched-off FC must never begin a new body takeover. Finish only
        // the bounded cleanup for water this chain itself already placed, so a
        // controlled test does not silently waste the rescue bucket.
        if (!companion.fcEnabled()) {
            return reclaiming(companion);
        }
        if (WorkProfile.of(companion).fearless()) {
            return false;
        }
        if (falling(companion)) {
            return true;
        }
        if (!reclaiming(companion)) {
            return false;
        }
        // 快窗口还是自救本身的一部分,照旧可以占身体(与改动前同性质)。
        if (reclaimTicks > 0) {
            return true;
        }
        // 清理阶段【绝不能压过救命反射】:本链注册号 10,压过岩浆逃逸(12)、
        // 窒息逃逸(15)、换气(20)、自卫(30),而 TaskSelector 取第一个 canRun 为真的。
        // 清理一旦在紧急情况期间仍报 true,就把那四条全压掉约 10 秒——在岩浆洞里足以致死。
        return cleanupMayYield(companion);
    }

    /** 此刻有没有救命反射该接手:有任何一条就让路。判据见 TempResourceRules。 */
    private static boolean cleanupMayYield(NumenPlayer companion) {
        return TempResourceRules.cleanupMayYield(
                companion.isDeadOrDying(),
                companion.isInLava(),
                companion.isInWall(),
                SurvivalDecisions.breathTriggered(
                        companion.isEyeInFluid(FluidTags.WATER), companion.getAirSupply()));
    }

    /** 正在快速下落,而且身上有能救自己的东西。 */
    private static boolean falling(NumenPlayer companion) {
        boolean grounded = companion.onGround() || companion.isInWater()
                || companion.isSwimming() || companion.onClimbable();
        boolean canSave = waterBucketSlot(companion) >= 0 || softBlockSlot(companion) >= 0;
        return SurvivalDecisions.mlgTriggered(grounded,
                companion.getDeltaMovement().y, canSave);
    }

    /**
     * 手上有空桶、记着的那格还是源水、而且还有窗口 —— 该去收。
     *
     * <p>注意这里<b>不</b>要求快窗口还剩:清理窗口期间同样要收,否则"快窗口到点就
     * 放弃"会原样复现,只是把 20 刻换成 200 刻而已。
     */
    private boolean reclaiming(NumenPlayer companion) {
        if (placed == null) {
            return false;
        }
        if (reclaimTicks <= 0 && cleanupTicks <= 0) {
            return false;
        }
        if (!canReclaimNow(companion)) {
            return false;   // 没空桶可装,或者已经收到手了
        }
        return placedWaterStillThere(companion);
    }

    /** 空桶在手、满桶不在手 —— 收水的动作条件齐了。 */
    private boolean canReclaimNow(NumenPlayer companion) {
        return slotWith(companion, Items.BUCKET) >= 0 && waterBucketSlot(companion) < 0;
    }

    /** 记着的那一格是否还是一摊源水(不是被别人收走、也不是流走了)。 */
    private boolean placedWaterStillThere(NumenPlayer companion) {
        if (placed == null) {
            return false;
        }
        BlockState state = companion.level().getBlockState(placed);
        return state.getFluidState().getType() == Fluids.WATER
                && state.getFluidState().isSource();
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        if (falling(companion)) {
            return clutch(companion);
        }
        if (placed == null) {
            return TaskState.RUNNING;
        }
        if (reclaimTicks > 0) {
            // 快窗口:到点或条件不成立,都必须让出身体 —— 但账不能销(见 settleFastWindow)。
            if (--reclaimTicks <= 0 || !reclaiming(companion)) {
                settleFastWindow(companion);
                return TaskState.RUNNING;
            }
            return reclaim(companion);
        }
        // 清理窗口:低优先级地把账销掉。仍然有上限,到点必须收手。
        if (--cleanupTicks <= 0 || !reclaiming(companion)) {
            closeLedger(companion);
            return TaskState.RUNNING;
        }
        return reclaim(companion);
    }

    /**
     * 快窗口到期。<b>让出身体,但不销账</b>:水还在就转清理窗口,水没了才算自然结清。
     * 旧实现在这里直接 {@code placed = null},那摊水就永久留在世界里而无人知晓。
     */
    private void settleFastWindow(NumenPlayer companion) {
        reclaimTicks = 0;
        switch (TempResourceRules.afterFastWindow(placedWaterStillThere(companion), canReclaimNow(companion))) {
            case SETTLED -> {
                placed = null;
                cleanupTicks = 0;
            }
            case DEFER_TO_CLEANUP -> cleanupTicks = CLEANUP_BUDGET_TICKS;
            case ABANDON_NO_BUCKET -> {
                reportUnreclaimed(companion, "no empty bucket was left to scoop it back up");
                placed = null;
                cleanupTicks = 0;
            }
        }
    }

    /** 清理窗口也到期:必须放手;水还在就留一句日记,让世界上的变化有据可查。 */
    private void closeLedger(NumenPlayer companion) {
        if (placedWaterStillThere(companion)) {
            reportUnreclaimed(companion, "the rescue window closed before I could scoop it back up");
        }
        placed = null;
        reclaimTicks = 0;
        cleanupTicks = 0;
    }

    /** 收不回来的落地水:报一次位置与原因,别让它变成"没人知道少了格水"。 */
    private void reportUnreclaimed(NumenPlayer companion, String why) {
        if (abandonNoted || placed == null) {
            return;
        }
        abandonNoted = true;
        com.dwinovo.numen.event.NumenEvents.body(companion,
                "left the water I poured for a fall at " + placed.toShortString()
                        + " — " + why + "; it is still there, so it will not save the next fall");
    }

    /** 摔落中:瞄住落点,够得着就倒水/垫块。 */
    private TaskState clutch(NumenPlayer companion) {
        BlockPos ground = groundBelow(companion);
        if (ground == null) {
            companion.setXRot(90.0f);   // 底下四十格没东西:先朝下候着
            return TaskState.RUNNING;
        }
        InputDriver.lookAt(companion, Vec3.atCenterOf(ground));

        BlockHitResult aim = bucketRay(companion, ClipContext.Fluid.NONE);
        if (aim.getType() != HitResult.Type.BLOCK || !aim.getBlockPos().equals(ground)) {
            return TaskState.RUNNING;   // 还够不着,或者这一刻没瞄准 —— 下一刻更近
        }

        // 下界的水一倒就蒸发,倒下去只是白扔一个桶。
        int bucket = companion.level().dimensionType().ultraWarm()
                ? -1 : waterBucketSlot(companion);
        if (bucket >= 0) {
            companion.holdInHand(bucket);
            placed = waterLandsAt(companion, aim);
            reclaimTicks = RECLAIM_TICKS;
            cleanupTicks = 0;
            abandonNoted = false;
            Interaction.useInAir(companion, InteractionHand.MAIN_HAND,
                    Interaction.Timing.once()).tick();
            noteSave(companion, "a water bucket");
            return TaskState.RUNNING;
        }
        int block = softBlockSlot(companion);
        if (block >= 0) {
            companion.holdInHand(block);
            // 记下"软方块会落在哪一格"要在点之前算:点完 world 就变了。
            // 以前这里记的是 ground(射线打到的支撑方块),而 useBlock 是贴面放置的,
            // 真正留在世界里的是它上面那一格 —— 记错坐标等于给后续收尾指错地方。
            BlockPos softAt = softBlockLandsAt(companion, aim);
            Interaction.useBlock(companion, aim, InteractionHand.MAIN_HAND).tick();
            noteSoftLeftBehind(companion, softAt, softPlacedOk(companion, softAt));
            noteSave(companion, "a soft block");
        }
        return TaskState.RUNNING;
    }

    /**
     * 软方块会落在哪一格 —— 与原版 {@code BlockItem.place} 同一算法:
     * 点到的格子若可被替换就就地放,否则贴住那个面(正下方看落点时就是它上面一格)。
     */
    private static BlockPos softBlockLandsAt(NumenPlayer companion, BlockHitResult hit) {
        BlockState state = companion.level().getBlockState(hit.getBlockPos());
        return state.canBeReplaced() ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
    }

    /** 回读那一格,确认真的垫上去了 —— 放不成功就别把坐标说得那么肯定。 */
    private static boolean softPlacedOk(NumenPlayer companion, BlockPos at) {
        BlockState s = companion.level().getBlockState(at);
        return s.is(Blocks.HAY_BLOCK) || s.is(Blocks.SLIME_BLOCK);
    }

    /**
     * 垫下去的软方块留在世界里,但只记一笔、<b>不自动拆</b>——她可能正站在上面,
     * 为了收尾把救命物抽走比留着更危险。要不要拆由上层决定。
     *
     * @param landedAt 真正放下方块的那一格(贴面放置,不是射线打到的支撑方块)
     * @param placedOk 回读确认：false 表示这一格并没有干草/史莱姆,别把坐标说得太肯定
     */
    private void noteSoftLeftBehind(NumenPlayer companion, BlockPos landedAt, boolean placedOk) {
        com.dwinovo.numen.event.NumenEvents.body(companion,
                "padded a fall with a soft block at " + landedAt.toShortString()
                        + (placedOk ? "" : " (I could not confirm it is there")
                        + "; I am not breaking it back up because I may be standing on it");
    }

    /** 落进自己那摊水:等沉稳了,换空桶把水收回来。 */
    private TaskState reclaim(NumenPlayer companion) {
        if (companion.getDeltaMovement().y < SurvivalDecisions.MLG_SETTLED_SPEED) {
            return TaskState.RUNNING;   // 还在往水里沉,等停稳再收
        }
        InputDriver.lookAt(companion, Vec3.atCenterOf(placed));
        // 空桶那条射线是认水源的(SOURCE_ONLY),和满桶那条不是同一种。
        BlockHitResult aim = bucketRay(companion, ClipContext.Fluid.SOURCE_ONLY);
        if (aim.getType() != HitResult.Type.BLOCK || !aim.getBlockPos().equals(placed)) {
            return TaskState.RUNNING;
        }
        int empty = slotWith(companion, Items.BUCKET);
        if (empty >= 0) {
            companion.holdInHand(empty);
            Interaction.useInAir(companion, InteractionHand.MAIN_HAND,
                    Interaction.Timing.once()).tick();
        }
        return TaskState.RUNNING;
    }

    /** One diary line per fall episode, stamped with the height it survived. */
    private void noteSave(NumenPlayer companion, String means) {
        if (notedThisFall) return;
        notedThisFall = true;
        com.dwinovo.numen.event.NumenEvents.body(companion, "broke a fall with " + means);
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        if (companion.isUsingItem()) {
            companion.releaseUsingItem();
        }
        companion.setXRot(0.0f);   // stop staring straight down; the resumed task re-aims as needed
        notedThisFall = false;     // the fall episode is over — the next fall diaries anew
        if (placed == null) {
            cleanupTicks = 0;
            reclaimTicks = 0;
            return;
        }
        // 收不回来就别留陈账。旧实现在这里无条件 placed=null(那是"落地水不回收"的成因),
        // 而单纯改成"有水就留"又会留下一个永远没人销的挂账:桶没了的时候 reclaiming()
        // 为 false → canRun() 为 false → tick() 永不执行 → 没人扣预算、也没人报放弃,
        // 一直挂到将来某个刻桶又回来了才复活(绕过 ABANDON_NO_BUCKET)。所以这里必须闭环:
        //   水没了        → 自然结清,销账
        //   没桶可装      → 收不回来,报一笔并销账
        //   水在且桶在    → 留账进清理窗口
        if (!placedWaterStillThere(companion)) {
            placed = null;
            reclaimTicks = 0;
            cleanupTicks = 0;
            return;
        }
        if (!canReclaimNow(companion)) {
            reportUnreclaimed(companion, "no empty bucket was left to scoop it back up");
            placed = null;
            reclaimTicks = 0;
            cleanupTicks = 0;
            return;
        }
        reclaimTicks = 0;
        // 预算只发一次,且每次被抢占都扣一格 —— 保证一定收敛,不会因为反复抢占
        // 而无限续期把这条反射永久钉在身体上(见 TempResourceRules 的说明)。
        if (cleanupTicks <= 0) {
            cleanupTicks = CLEANUP_BUDGET_TICKS;
        } else {
            cleanupTicks--;
        }
    }

    @Override
    public String name() {
        return "mlg";
    }

    // ---- Reflex roster paperwork (constitution §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "高处坠落时会用水桶或软方块自救,落地后把水收回来";
    }

    /**
     * 原版 {@code BucketItem} 自己那条射线:从眼睛沿视线打 {@code blockInteractionRange}。
     * 判"够不够得着"和真正执行用的是同一条,所以不会有点了没反应的情况。
     */
    private static BlockHitResult bucketRay(NumenPlayer companion, ClipContext.Fluid fluids) {
        Vec3 eye = companion.getEyePosition();
        Vec3 end = eye.add(companion.calculateViewVector(companion.getXRot(), companion.getYRot())
                .scale(companion.blockInteractionRange()));
        return companion.level().clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, fluids, companion));
    }

    /** 水会落在哪一格 —— 与 {@code BucketItem.use} 同一个算法(可含水的方块就地灌,否则贴面)。 */
    private static BlockPos waterLandsAt(NumenPlayer companion, BlockHitResult hit) {
        BlockState state = companion.level().getBlockState(hit.getBlockPos());
        return state.getBlock() instanceof LiquidBlockContainer
                ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
    }

    /**
     * 她会砸到的那一格。碰撞箱中心加四角各打一条竖直射线,取最近的那个 —— 单一条会从
     * 格缝里漏下去,而擦着边掉正是最需要救的那种。
     */
    private static BlockPos groundBelow(NumenPlayer companion) {
        AABB box = companion.getBoundingBox();
        double y = companion.getY();
        Vec3[] origins = {
                new Vec3(companion.getX(), y, companion.getZ()),
                new Vec3(box.minX, y, box.minZ), new Vec3(box.maxX, y, box.minZ),
                new Vec3(box.minX, y, box.maxZ), new Vec3(box.maxX, y, box.maxZ),
        };
        BlockPos best = null;
        double bestDrop = Double.MAX_VALUE;
        for (Vec3 from : origins) {
            BlockHitResult hit = companion.level().clip(new ClipContext(
                    from, from.add(0.0, -PROBE_DEPTH, 0.0),
                    ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, companion));
            if (hit.getType() != HitResult.Type.BLOCK) {
                continue;
            }
            double drop = y - hit.getLocation().y;
            if (drop < bestDrop) {
                bestDrop = drop;
                best = hit.getBlockPos();
            }
        }
        return best;
    }

    private static int waterBucketSlot(NumenPlayer companion) {
        return slotWith(companion, Items.WATER_BUCKET);
    }

    private static int slotWith(NumenPlayer companion, net.minecraft.world.item.Item item) {
        Inventory inv = companion.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) return i;
        }
        return -1;
    }

    /** Slot of a placeable fall-dampening block (hay / slime), or -1. */
    private static int softBlockSlot(NumenPlayer companion) {
        Inventory inv = companion.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.HAY_BLOCK) || s.is(Items.SLIME_BLOCK)) return i;
        }
        return -1;
    }
}
