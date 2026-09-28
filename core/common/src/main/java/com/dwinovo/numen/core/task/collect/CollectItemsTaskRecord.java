package com.dwinovo.numen.core.task.collect;

import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.item.Item;

import java.util.Set;

/**
 * Typed task descriptor for the {@code collect_items} tool: "walk around and
 * pick up dropped items nearby". The goal ({@link CollectItemsCompanionTask}) scans
 * for {@code ItemEntity}s within the radius, walks to each with the pathfinder
 * (the entity auto-absorbs items it gets close to), and repeats until none
 * remain. An optional {@link #filter} restricts to specific item types; empty
 * means collect everything.
 */
public final class CollectItemsTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "collect_items";

    /** Item types to collect; empty = collect every dropped item. */
    public final Set<Item> filter;
    /** Search radius in blocks. */
    public final int radius;
    /** Human-readable label for messages (e.g. "all items" or "diamond"). */
    public final String label;

    /** Live progress, updated by the goal as items are absorbed. */
    private int collected = 0;
    /** 目标消失但背包没涨的件数(被烧/被抢/despawn)——不算战果,但必须报出来。 */
    private int vanished = 0;
    /** 寻路失败够不着的件数。 */
    private int unreachable = 0;
    /** 走到了却没吸上、被本轮跳过的件数。 */
    private int leftBehind = 0;
    /**
     * 是否真的扫到了"再没有候选"那一步。超时/取消时任务根本没走完一圈,
     * 三个缺口计数都还是 0 —— 没有这条,「一件都没拿到」会被报成 all_picked_up=true。
     */
    private boolean reachedEnd = false;

    public CollectItemsTaskRecord(String toolCallId, long deadlineGameTime,
                                  Set<Item> filter, int radius, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.filter = Set.copyOf(filter);
        this.radius = radius;
        this.label = label;
    }

    public int getCollected() {
        return collected;
    }

    public void incrementCollected() {
        this.collected++;
    }

    /** 记一次"东西没了但不是我们拿的",让上层看得见缺口而不是只看到一个 SUCCESS。 */
    public void noteVanished() {
        this.vanished++;
    }

    public int getVanished() {
        return vanished;
    }

    /** 记一次"寻路够不着"。 */
    public void noteUnreachable() {
        this.unreachable++;
    }

    public int getUnreachable() {
        return unreachable;
    }

    /** 记一次"走到了却没吸上"。 */
    public void noteLeftBehind() {
        this.leftBehind++;
    }

    public int getLeftBehind() {
        return leftBehind;
    }

    /** 扫到"再没有候选"时打一个印记——只有真的走完一圈才允许说"都拿到了"。 */
    public void markReachedEnd() {
        this.reachedEnd = true;
    }

    public boolean reachedEnd() {
        return reachedEnd;
    }

    /**
     * 这一趟是不是干净完成:真的走完一圈,且没有在到达前消失的、没有够不着的、
     * 没有走到却丢下的。与"扫完了"(SCAN 再也找不到候选)不是一回事——这正是
     * "黑曜石挖了不捡"能被误判成完成的缺口。
     */
    public boolean isSweepComplete() {
        return CollectDecisions.sweepComplete(reachedEnd, vanished, unreachable, leftBehind);
    }

    @Override
    /**
     * 一行摘要——这是<b>给主人看</b>的:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地盘(运行时状态的 tool 属性、派发回路)本来就有。
     */
    public String describe() {
        return "收集 " + label + " x" + collected;
    }
}
