package com.dwinovo.numen.core.task.sugarcane;

import java.util.ArrayList;
import java.util.List;

/**
 * 有界甘蔗目标规划（2026-11-07，记录 008 §9.1/§9.2）。纯逻辑，不碰世界：
 * 每株保留最底根（y=base），只取上段；数量与期限由调用方预算；不预测入包件数。
 *
 * <p>未加载/只有底根由调用方跳过并计入 tried；本类只回答“这一株要破坏哪些格”。</p>
 */
public final class SugarCaneHarvestPlan {
    private SugarCaneHarvestPlan() {}

    /** 一株的格 y 列表（从下到上、同一水平位置），返回要破坏的上段 y（保留下标 0）。 */
    public static List<Integer> segmentsToBreak(List<Integer> columnYs, int remainingBudget) {
        if (columnYs == null || columnYs.size() < 2 || remainingBudget <= 0) return List.of();
        List<Integer> out = new ArrayList<>();
        for (int i = 1; i < columnYs.size() && out.size() < remainingBudget; i++) {
            out.add(columnYs.get(i));
        }
        return List.copyOf(out);
    }

    /** 只用于判定：该株是否“只有底根”（无可收上段）。 */
    public static boolean hasHarvestableSegment(List<Integer> columnYs) {
        return columnYs != null && columnYs.size() >= 2;
    }
}
