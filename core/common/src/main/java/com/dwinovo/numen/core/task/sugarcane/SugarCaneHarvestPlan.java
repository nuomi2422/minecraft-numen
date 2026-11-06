package com.dwinovo.numen.core.task.sugarcane;

import java.util.ArrayList;
import java.util.List;

/**
 * 有界甘蔗目标规划（2026-11-07，记录 008 §9.1/§9.2；修订：从上往下）。
 * 每株保留最底根；若 count 小于上段长度，**只取最高的 count 格**，并返回**自上而下**的执行顺序——
 * 与真实破坏顺序一致，避免从靠近根部下手导致上方连锁掉落绕过 count/统计。
 */
public final class SugarCaneHarvestPlan {
    private SugarCaneHarvestPlan() {}

    /** columnYs 从下到上；返回要破坏的上段 y（**自上而下**），底根永远保留。 */
    public static List<Integer> segmentsToBreak(List<Integer> columnYs, int remainingBudget) {
        if (columnYs == null || columnYs.size() < 2 || remainingBudget <= 0) return List.of();
        int upper = columnYs.size() - 1;                 // 上段格数，不含根
        int take = Math.min(upper, remainingBudget);
        List<Integer> out = new ArrayList<>(take);
        for (int i = columnYs.size() - 1; i >= 1 && out.size() < take; i--) {
            out.add(columnYs.get(i));                    // 先最高格
        }
        return List.copyOf(out);
    }

    public static boolean hasHarvestableSegment(List<Integer> columnYs) {
        return columnYs != null && columnYs.size() >= 2;
    }
}
