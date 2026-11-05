package com.dwinovo.numen.core.task.interact;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * 「交互被挡住时，告诉它该站哪」的纯逻辑（2026-11-05）。
 *
 * <p>背景（实测）：{@code aim … is blocked from here} 在请求历史里出现上千次、涉及 34 个坐标，
 * 提示里已经写了「换个开放侧」，但模型没照做——它缺的不是理由，是**一个具体坐标**。
 *
 * <p>本类只做候选与挑选：站得下（{@code standable}）与看得见（{@code visible}）由调用方注入，
 * 所以可以离线测，不需要 Minecraft。
 */
public final class InteractSightHint {

    /** 最远试到 2 格（仍要在交互距离内，且不必让调用方满世界搜）。 */
    static final int MAX_RADIUS = 2;

    private InteractSightHint() {}

    /** 候选站位的顺序：距离近的优先，同距离按固定顺序（保证可复现）。 */
    static List<int[]> candidates(int tx, int ty, int tz) {
        List<int[]> out = new ArrayList<>();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -MAX_RADIUS; dx <= MAX_RADIUS; dx++) {
                for (int dz = -MAX_RADIUS; dz <= MAX_RADIUS; dz++) {
                    if (dx == 0 && dz == 0) continue;          // 站在目标格里不算
                    int d = Math.abs(dx) + Math.abs(dz);
                    if (d > MAX_RADIUS + 1) continue;
                    out.add(new int[] { tx + dx, ty + dy, tz + dz, d });
                }
            }
        }
        out.sort(Comparator.<int[]>comparingInt(a -> a[3]).thenComparingInt(a -> a[0])
                .thenComparingInt(a -> a[1]).thenComparingInt(a -> a[2]));
        return out;
    }

    /**
     * 挑第一个「站得下 且 看得见」的位置。
     *
     * @return {x,y,z}，没有就返回 null（调用方保持原提示，不编造位置）
     */
    static int[] pick(int tx, int ty, int tz, Predicate<int[]> standable, Predicate<int[]> visible) {
        for (int[] c : candidates(tx, ty, tz)) {
            int[] p = new int[] { c[0], c[1], c[2] };
            if (standable != null && !standable.test(p)) continue;
            if (visible != null && !visible.test(p)) continue;
            return p;
        }
        return null;
    }
}
