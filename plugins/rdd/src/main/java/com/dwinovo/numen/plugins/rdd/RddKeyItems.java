package com.dwinovo.numen.plugins.rdd;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 2026-11-07：背包“关键物品”行（只读 SELF 观测缓存，纯函数可测；不碰容器/服务端数据）。 */
final class RddKeyItems {
    private RddKeyItems() {}

    static final int MAX = 12;
    private static final Set<String> EXACT = Set.of(
            "minecraft:iron_chestplate", "minecraft:diamond_chestplate", "minecraft:netherite_chestplate",
            "minecraft:iron_helmet", "minecraft:diamond_helmet", "minecraft:netherite_helmet",
            "minecraft:iron_leggings", "minecraft:diamond_leggings", "minecraft:netherite_leggings",
            "minecraft:iron_boots", "minecraft:diamond_boots", "minecraft:netherite_boots",
            "minecraft:shield", "minecraft:bow", "minecraft:crossbow", "minecraft:fishing_rod",
            "minecraft:flint_and_steel", "minecraft:shears", "minecraft:bread", "minecraft:apple",
            "minecraft:iron_ingot", "minecraft:gold_ingot", "minecraft:diamond", "minecraft:coal",
            "minecraft:torch", "minecraft:water_bucket", "minecraft:bucket", "minecraft:wheat",
            "minecraft:iron_pickaxe", "minecraft:diamond_pickaxe", "minecraft:iron_sword", "minecraft:diamond_sword",
            "minecraft:iron_axe", "minecraft:diamond_axe", "minecraft:iron_shovel", "minecraft:diamond_shovel",
            "minecraft:iron_hoe", "minecraft:diamond_hoe");
    private static final List<String> SUFFIX = List.of(
            "_helmet", "_chestplate", "_leggings", "_boots", "_sword", "_pickaxe", "_axe", "_shovel", "_hoe",
            "_bow", "_rod", "_bucket", "_torch");
    private static final List<String> FOOD = List.of(
            "minecraft:cooked_", "minecraft:raw_", "minecraft:bread", "minecraft:apple", "minecraft:carrot",
            "minecraft:potato", "minecraft:beetroot", "minecraft:sweet_berries", "minecraft:melon_slice");

    /** @return 行文本；null=该行不显示（库存未知/无关键物品）。 */
    static String line(Object rawSlots) { return line(rawSlots, Integer.MAX_VALUE); }

    /** 预算内版本：只截关键物品行本身，超长带省略标记；null=整行不显示。 */
    static String line(Object rawSlots, int maxChars) {
        if (!(rawSlots instanceof List<?> slots) || slots.size() != 36) return null;
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Object o : slots) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Object id = m.get("id");
            if (!(id instanceof String s) || s.isBlank()) continue;
            int n = m.get("count") instanceof Number num ? Math.max(0, num.intValue()) : 1;
            if (n <= 0) continue;
            if (wanted(s)) counts.merge(s, n, Integer::sum);
        }
        if (counts.isEmpty()) return null;
        List<String> ids = new ArrayList<>(counts.keySet());
        ids.sort(Comparator.naturalOrder());
        List<String> shown = new ArrayList<>(MAX);
        for (int i = 0; i < ids.size() && i < MAX; i++) {
            String id = ids.get(i);
            int n = counts.get(id);
            shown.add(id.replace("minecraft:", "") + (n > 1 ? "×" + n : ""));
        }
        while (true) {
            String s = String.join("、", shown);
            int omitted = ids.size() - shown.size();
            String tail = omitted > 0 ? "…（省略" + omitted + "种）" : "";
            String line = "关键物品 " + s + tail;
            if (line.length() <= maxChars || shown.isEmpty()) return line;
            shown.remove(shown.size() - 1);
        }
    }

    private static boolean wanted(String id) {
        if (!id.startsWith("minecraft:")) return false;
        if (EXACT.contains(id)) return true;
        for (String suf : SUFFIX) if (id.endsWith(suf)) return true;
        for (String f : FOOD) if (id.startsWith(f)) return true;
        return false;
    }
}
