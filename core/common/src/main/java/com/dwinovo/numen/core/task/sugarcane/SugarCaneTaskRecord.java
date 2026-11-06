package com.dwinovo.numen.core.task.sugarcane;

import com.dwinovo.numen.task.TaskRecord;

/**
 * 有界甘蔗收割任务参数（2026-11-07，记录 008 §9.1/§9.2/§9.3）。
 *
 * <p>来源线索：TartaricAcid/TouhouLittleMaid {@code TaskSugarCane.java}（MIT，commit
 * 207647c85740b1d0971de6040d6dd57cea62528c）的“留根、只收上段”语义；实现由本仓 BlockDigger 受控破坏承担，
 * 未复制上游代码/资产。目的：把单位/上限/期限一次性定死，避免 count=0 或无限收割。</p>
 *
 * <p>count = **实际破坏的上段格数**（不含保留底根），不是株数、不是入包件数；原版拾取统计不能在这里预言。</p>
 */
public final class SugarCaneTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "sugar_cane_harvest";
    public static final int MAX_COUNT = 64;
    public static final int MAX_RADIUS = 16;
    public static final long MAX_DEADLINE_MS = 120_000L;
    public static final int MAX_TARGETS = 16;

    /** 目标破碎上段格数上限（1..64）。 */
    public final int count;
    /** 尝试株数上限（1..16）。 */
    public final int maxTargets;
    /** 水平半径（1..16）。 */
    public final int radius;
    /** 绝对截止时间（毫秒时间戳）。 */
    public final long deadlineMs;

    public SugarCaneTaskRecord(int count, int maxTargets, int radius, long deadlineMs, long nowMs) {
        super(TOOL_NAME, "", 0L);
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("count 必须在 1.." + MAX_COUNT + "（实际破坏上段格数），收到 " + count);
        }
        if (maxTargets < 1 || maxTargets > MAX_TARGETS) {
            throw new IllegalArgumentException("maxTargets 必须在 1.." + MAX_TARGETS + "，收到 " + maxTargets);
        }
        if (radius < 1 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("radius 必须在 1.." + MAX_RADIUS + "，收到 " + radius);
        }
        long cap = nowMs + Math.min(MAX_DEADLINE_MS, count * 2_000L);
        if (deadlineMs <= nowMs || deadlineMs > cap) {
            throw new IllegalArgumentException("deadline 必须在 (now, now+min(120s,count*2s)] 内，收到 "
                    + deadlineMs + " cap=" + cap);
        }
        this.count = count;
        this.maxTargets = maxTargets;
        this.radius = radius;
        this.deadlineMs = deadlineMs;
    }
}
