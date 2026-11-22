package com.dwinovo.numen.core.task.mine;

import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.BlockPos;

import java.util.Set;

/**
 * Typed task descriptor for the intent-level {@code mine} tool: "gather
 * {@code count} of these block types, search/pathfind/dig it yourself". The
 * task owns the whole loop — scan the loaded area around the body for
 * targets, walk to them with the terrain-modifying pathfinder (bridging /
 * digging as needed), mine into the entity inventory, repeat until the count
 * is met or nothing reachable remains.
 *
 * <p>The LLM never sees coordinates: it only declares <em>what</em> and
 * <em>how many</em>. Drops/tool-tier follow from whatever the entity holds,
 * as in vanilla.
 */
public final class MineBlockTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "mine";

    /** Block types to gather (include variants, e.g. iron_ore + deepslate_iron_ore). */
    public final Set<Block> targets;
    /** How many to gather before reporting success. */
    public final int count;
    /** Human-readable target label for messages / debug overlay (e.g. "iron_ore"). */
    public final String label;
    private final BlockPos targetMin;
    private final BlockPos targetMax;

    /** Live progress = matching ITEMS gathered since the task started (counted in the inventory,
     *  not blocks broken — multi-drop ores like redstone yield several items per block). Set each tick
     *  by the task; drives the stop condition + the debug overlay text. */
    private int mined = 0;

    public MineBlockTaskRecord(String toolCallId, long deadlineGameTime,
                               Set<Block> targets, int count, String label) {
        this(toolCallId, deadlineGameTime, targets, count, label, null, null);
    }

    /** Ore target bounds only; not a route or terrain-modification boundary. */
    public MineBlockTaskRecord(String toolCallId, long deadlineGameTime,
                               Set<Block> targets, int count, String label,
                               BlockPos first, BlockPos second) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        if ((first == null) != (second == null))
            throw new IllegalArgumentException("target bounds require both corners");
        targetMin = first == null ? null : new BlockPos(Math.min(first.getX(), second.getX()),
                Math.min(first.getY(), second.getY()), Math.min(first.getZ(), second.getZ()));
        targetMax = first == null ? null : new BlockPos(Math.max(first.getX(), second.getX()),
                Math.max(first.getY(), second.getY()), Math.max(first.getZ(), second.getZ()));
        this.targets = Set.copyOf(targets);
        this.count = count;
        this.label = label;
    }

    /** Pure, immutable candidate filter; does not inspect the body or the world. */
    public boolean containsTarget(BlockPos pos) {
        return targetMin == null || (pos.getX() >= targetMin.getX() && pos.getX() <= targetMax.getX()
                && pos.getY() >= targetMin.getY() && pos.getY() <= targetMax.getY()
                && pos.getZ() >= targetMin.getZ() && pos.getZ() <= targetMax.getZ());
    }

    public int getMined() {
        return mined;
    }

    /** Set the running item-gathered tally (the task recomputes it from the inventory each tick). */
    public void setMined(int gathered) {
        this.mined = gathered;
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        return "挖 " + label + " " + mined + "/" + count;
    }
}
