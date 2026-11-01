package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 基地备用箱的**最低清单**（167 §B5）。
 *
 * <p>为什么要有这张表：167 只说"最近扫描命中最低备用清单"，没给数量；
 * 而"箱子读到了但里面是空的"和"箱子有备货"必须能分开判，
 * 否则 {@code STOCKED} 这一档永远靠感觉。
 *
 * <p>取值刻意保守：**一套能重新开工 + 能活下去的最低量**，不是"下界准备"那种高配。
 * 高配由 {@code RiskGate} 在具体高风险动作前单独检查，不混进基地阶段。
 *
 * <p>铁镐是"能重新挖"，食物是"能撑到回来"，火把是"夜里能看见路"。
 */
public final class ReserveKit {

    private ReserveKit() {}

    /** 最低备用：能重新开工 + 能活过一晚。 */
    public static final Map<String, Integer> MINIMUM = Map.of(
            "minecraft:iron_pickaxe", 1,
            "minecraft:cooked_beef", 8,
            "minecraft:torch", 8);

    /** 达到最低清单。{@code null} 视为未知（不是"够"）。 */
    public static boolean sufficient(Map<String, Integer> reserve) {
        return reserve != null && shortfall(reserve).isEmpty();
    }

    /** 逐项短缺，形如 {@code iron_pickaxe 3/8}（按 id 排序，便于稳定 diff）。 */
    public static List<String> shortfall(Map<String, Integer> reserve) {
        List<String> out = new ArrayList<>();
        if (reserve == null) return out;
        MINIMUM.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> {
                    int have = reserve.getOrDefault(e.getKey(), 0);
                    if (have < e.getValue()) {
                        out.add(e.getKey().replace("minecraft:", "") + " " + have + "/" + e.getValue());
                    }
                });
        return List.copyOf(out);
    }
}
