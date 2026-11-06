package com.dwinovo.numen.plugins.rdd;

import java.util.List;
import java.util.Map;

/**
 * 播种的纯规则（2026-11-06）：种子 → 作物、以及“先种哪一种”的优先级。
 *
 * <p>为什么要单独抽出来：{@code Items}/{@code Blocks} 在单测类路径上不存在，
 * 规则留在工具里就永远测不到；而“种错作物”“没种子还硬种”恰恰是这类工具最容易出的错。
 * 这里只做字符串映射，工具侧再拿 id 去注册表取真对象。
 */
final class SowRules {

    /** 优先级：小麦 → 胡萝卜 → 土豆 → 甜菜根（背包里有哪一种就先种哪一种）。 */
    static final List<String> SEED_PRIORITY = List.of(
            "minecraft:wheat_seeds", "minecraft:carrot", "minecraft:potato", "minecraft:beetroot_seeds");

    private SowRules() {}

    /** 种子 id → 作物方块 id；不认识返回 null（不猜成小麦）。 */
    static String cropBlockIdForSeed(String seedId) {
        if (seedId == null) return null;
        return switch (seedId) {
            case "minecraft:wheat_seeds" -> "minecraft:wheat";
            case "minecraft:carrot" -> "minecraft:carrots";
            case "minecraft:potato" -> "minecraft:potatoes";
            case "minecraft:beetroot_seeds" -> "minecraft:beetroots";
            default -> null;
        };
    }

    /** 按优先级挑出第一个数量>0 的种子 id；都没有返回 null。 */
    static String pickSeedId(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) return null;
        for (String id : SEED_PRIORITY) {
            Integer n = counts.get(id);
            if (n != null && n > 0) return id;
        }
        return null;
    }
}
