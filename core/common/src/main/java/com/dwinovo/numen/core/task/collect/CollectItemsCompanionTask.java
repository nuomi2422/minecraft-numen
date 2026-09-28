package com.dwinovo.numen.core.task.collect;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.TargetSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Intent-level item sweeper for {@link CollectItemsTaskRecord}: "pick up the
 * dropped items around here." The entity already auto-absorbs items within ~1
 * block ({@code setCanPickUpLoot}); this goal actively walks it to each
 * scattered drop with the pathfinder so nothing is left behind after a mine or a
 * attack.
 *
 * <h2>State machine (per tick)</h2>
 * <pre>
 *   SCAN     → nearest matching ItemEntity within the radius; none → DONE.
 *   APPROACH → Navigator toward it until it disappears, or we reach the spot
 *              without absorbing it (leftBehind, then re-SCAN), or the path fails
 *              (unreachable, then re-SCAN).
 *   SETTLE   → the drop is gone; give the inventory a few ticks to be credited,
 *              then settle the books by DELTA (see below).
 * </pre>
 *
 * <h2>消失 ≠ 拿到（这是这一版修的东西）</h2>
 * 旧实现看见 {@code isRemoved()} 就 {@code collected++}。可它是被烧了、被别人捡了、
 * 还是原版 despawn 了,一概算成"我的战果",于是工具报"扫完了 n 件"而背包里没有 n 件,
 * 上层看到 SUCCESS 就打勾——"黑曜石挖了不捡"正是这样被当成完成的。
 *
 * <p>现在归属只由<b>背包增量</b>证明(判据见 {@link CollectDecisions}):出发时记下
 * 这一种物品的持有量,东西消失后等 {@link #SETTLE_TICKS} 刻让原版把拾取结算完,再对账。
 * 对不上的记 {@code vanished},走到了没吸上的记 {@code leftBehind},够不着的记
 * {@code unreachable}——三者都会进 {@code resultData} 和收尾文案,并且让
 * {@link CollectItemsTaskRecord#isSweepComplete()} 为 false,于是"扫完了"和"拿到了"
 * 从此能被上层分开看。
 *
 * <p>终态仍是 SUCCESS:工具契约没动(扫完一圈确实扫完了),变的是它<b>如实报账</b>。
 */
public final class CollectItemsCompanionTask extends AbstractCompanionTask<CollectItemsTaskRecord> {

    private enum Phase { SCAN, APPROACH, SETTLE }

    private static final double WALK_SPEED = 1.0;
    /** Close enough that vanilla auto-pickup should have absorbed the item (≈1.2 blocks). */
    private static final double PICKUP_REACH_SQR = 1.5;
    /**
     * 东西消失后给背包落账的宽限刻数。原版拾取与 {@code isRemoved()} 不保证同刻结算,
     * 当场读背包会差一格,正好把"拿到了"误判成"消失了"——那就等于没修。
     */
    private static final int SETTLE_TICKS = 3;

    private Phase phase = Phase.SCAN;
    private ItemEntity target;
    /** 本轮目标在出发时背包里的持有量——归属靠它和消失后的读数对账。 */
    private Item pendingItem;
    private int pendingBaseline;
    private int settleTicks;
    /** 收尾时只报一次,免得每轮 sweep 都在日志里喊同一件事。 */
    private boolean shortfallNoted;

    /** Item-entity ids we reached but couldn't absorb, so SCAN won't loop on them. */
    private final TargetSet<ItemEntity> skipped = new TargetSet<>(ItemEntity::getId);

    public CollectItemsCompanionTask(NumenPlayer player, CollectItemsTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        this.phase = Phase.SCAN;
        this.target = null;
        this.pendingItem = null;
        this.settleTicks = 0;
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) {
            return TaskState.CANCELLED;
        }
        return switch (phase) {
            case SCAN -> tickScan();
            case APPROACH -> tickApproach();
            case SETTLE -> tickSettle();
        };
    }

    private TaskState tickScan() {
        ItemEntity best = nearestItem();
        if (best == null) {
            // Nothing left within radius — this sweep is over. Whether it actually GOT
            // everything is a separate question, answered by the tallies, not by this state.
            noteShortfallOnce();
            return TaskState.SUCCESS;
        }
        target = best;
        pendingItem = best.getItem().getItem();
        pendingBaseline = held(pendingItem);
        settleTicks = SETTLE_TICKS;
        nav = new PlayerNav(player, this::targetCell, WALK_SPEED, this::picked);
        phase = Phase.APPROACH;
        return TaskState.RUNNING;
    }

    private TaskState tickApproach() {
        if (target == null) {
            stopNav();
            phase = Phase.SCAN;
            return TaskState.RUNNING;
        }
        if (target.isRemoved()) {
            // Gone — but gone is not "ours" yet. Hand over to SETTLE and settle by delta.
            stopNav();
            phase = Phase.SETTLE;
            return TaskState.RUNNING;
        }
        switch (nav.tick()) {
            case RUNNING -> { /* walking to it */ }
            case ARRIVED -> {
                // Reached the spot and it is still lying there: vanilla auto-pickup did not
                // take it (pickup delay, or a spot we can stand next to but not absorb from).
                // Skip it so SCAN can't spin on the same drop — and say so in the books.
                if (!target.isRemoved()) {
                    skipped.skip(target);
                    r.noteLeftBehind();
                    target = null;
                    stopNav();
                    phase = Phase.SCAN;
                }
            }
            case FAILED -> {                 // can't route to it — abandon, and record why
                if (target != null) {
                    skipped.skip(target);
                    r.noteUnreachable();
                }
                target = null;
                stopNav();
                phase = Phase.SCAN;
            }
        }
        return TaskState.RUNNING;
    }

    /** 东西已经不在世界里了:等背包落完账,再按增量结账。 */
    private TaskState tickSettle() {
        if (--settleTicks > 0) {
            return TaskState.RUNNING;
        }
        int delta = pendingItem == null ? 0 : held(pendingItem) - pendingBaseline;
        CollectDecisions.Outcome outcome = CollectDecisions.classify(true, delta);
        for (int i = CollectDecisions.creditFor(outcome); i > 0; i--) {
            r.incrementCollected();
        }
        if (outcome == CollectDecisions.Outcome.VANISHED) {
            r.noteVanished();
        }
        target = null;
        pendingItem = null;
        stopNav();
        phase = Phase.SCAN;
        return TaskState.RUNNING;
    }

    private BlockPos targetCell() {
        return (target != null && !target.isRemoved()) ? target.blockPosition() : null;
    }

    /** Reached = absorbed, or close enough that auto-pickup should have fired. */
    private boolean picked() {
        return target == null || target.isRemoved()
                || player.distanceToSqr(target) <= PICKUP_REACH_SQR;
    }

    private ItemEntity nearestItem() {
        AABB box = player.getBoundingBox().inflate(r.radius);
        List<ItemEntity> candidates = new ArrayList<>();
        for (Entity e : player.level().getEntities(player, box)) {
            if (!(e instanceof ItemEntity ie) || ie.isRemoved()) continue;
            if (!r.filter.isEmpty() && !r.filter.contains(ie.getItem().getItem())) continue;
            candidates.add(ie);
        }
        return skipped.pick(candidates, Comparator.comparingDouble(player::distanceToSqr)).orElse(null);
    }

    /** 背包里这一种物品的总件数——归属对账的读数端。 */
    private int held(Item item) {
        if (item == null) {
            return 0;
        }
        Inventory inv = player.getInventory();
        int total = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(item)) {
                total += inv.getItem(i).getCount();
            }
        }
        return total;
    }

    /**
     * 这一趟有缺口时留一句日记。原版收尾文案只说"扫完了 n 件",认知层看不见
     * "有 n 件根本没到手"——那句话正是上层误判完成的源头。
     */
    private void noteShortfallOnce() {
        if (shortfallNoted || r.isSweepComplete()) {
            return;
        }
        shortfallNoted = true;
        com.dwinovo.numen.event.NumenEvents.body(player,
                "swept " + r.label + " but only " + r.getCollected() + " actually reached my inventory"
                        + " — " + r.getVanished() + " vanished before pickup, "
                        + r.getUnreachable() + " out of reach, "
                        + r.getLeftBehind() + " left on the ground");
    }

    @Override
    protected void cleanup() {
        super.cleanup();
        target = null;
        pendingItem = null;
        phase = Phase.SCAN;
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("label", r.label);
        data.put("collected", r.getCollected());
        data.put("radius", r.radius);
        // 缺口分项:上层不必去解析文案就能知道"扫完了"不等于"拿到了"。
        data.put("vanished", r.getVanished());
        data.put("unreachable", r.getUnreachable());
        data.put("left_behind", r.getLeftBehind());
        data.put("all_picked_up", r.isSweepComplete());
        return data;
    }

    @Override
    protected String successMessage() {
        return "collected " + r.getCollected() + " " + r.label + shortfallSuffix();
    }

    @Override
    protected String timeoutMessage() {
        return "timed out after collecting " + r.getCollected() + " " + r.label + shortfallSuffix();
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after collecting " + r.getCollected() + " " + r.label + shortfallSuffix();
    }

    /** 有缺口就把缺口说清楚,别让"扫完了"听着像"都拿到了"。 */
    private String shortfallSuffix() {
        if (r.isSweepComplete()) {
            return "";
        }
        return " (short: " + r.getVanished() + " vanished, " + r.getUnreachable() + " unreachable, "
                + r.getLeftBehind() + " left on the ground)";
    }
}
