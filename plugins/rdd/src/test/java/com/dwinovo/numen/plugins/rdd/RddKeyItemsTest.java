package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07：关键物品行——有装备要出现、无关键物品/未知库存整行不显示、>12 截断有省略标记。 */
class RddKeyItemsTest {
    private static List<Object> slots(Object... entries) {
        List<Object> out = new ArrayList<>();
        for (int i = 0; i < 36; i++) out.add(null);
        for (int i = 0; i < entries.length && i < 36; i++) out.set(i, entries[i]);
        return out;
    }
    private static Map<String, Object> item(String id, int n) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id); m.put("count", n);
        return m;
    }

    @Test void backupChestplateAppears() {
        String line = RddKeyItems.line(slots(item("minecraft:iron_chestplate", 1), item("minecraft:wheat", 3)));
        assertNotNull(line);
        assertTrue(line.contains("iron_chestplate"), line);
        assertTrue(line.contains("wheat×3"), line);
    }
    @Test void unknownSlotsOrNoKeyItemsHideLine() {
        assertNull(RddKeyItems.line("not a list"));
        assertNull(RddKeyItems.line(slots(item("minecraft:dirt", 5))), "只有建材垃圾不占行");
    }
    @Test void capsAtTwelveWithEllipsis() {
        Object[] many = new Object[16];
        for (int i = 0; i < many.length; i++) many[i] = item("minecraft:iron_pickaxe", 1);
        // 同一 id 去重后只有 1 项；改用 16 个不同工具
        String[] ids = {"minecraft:iron_pickaxe","minecraft:iron_axe","minecraft:iron_shovel","minecraft:iron_hoe",
                "minecraft:iron_sword","minecraft:diamond_pickaxe","minecraft:diamond_axe","minecraft:diamond_shovel",
                "minecraft:diamond_hoe","minecraft:diamond_sword","minecraft:bow","minecraft:crossbow",
                "minecraft:shield","minecraft:shears","minecraft:fishing_rod","minecraft:flint_and_steel"};
        for (int i = 0; i < ids.length; i++) many[i] = item(ids[i], 1);
        String line = RddKeyItems.line(slots(many));
        assertNotNull(line);
        assertTrue(line.contains("…（省略4种）"), line);
    }
    @Test void budgetTruncatesOnlyKeyLineWithEllipsis() {
        String[] ids = {"minecraft:iron_pickaxe","minecraft:iron_axe","minecraft:iron_shovel","minecraft:iron_hoe",
                "minecraft:iron_sword","minecraft:diamond_pickaxe","minecraft:diamond_axe","minecraft:diamond_shovel",
                "minecraft:diamond_hoe","minecraft:diamond_sword","minecraft:bow","minecraft:crossbow"};
        Object[] many = new Object[ids.length];
        for (int i = 0; i < ids.length; i++) many[i] = item(ids[i], 1);
        String line = RddKeyItems.line(slots(many), 60);
        assertNotNull(line);
        assertTrue(line.length() <= 60, "必须在预算内: " + line.length());
        assertTrue(line.contains("省略"), "截断要有省略标记: " + line);
        assertTrue(line.startsWith("关键物品 "), "关键物品行本身才截，前缀保留: " + line);
    }
    @Test void sortedByItemId() {
        String line = RddKeyItems.line(slots(item("minecraft:wheat", 1), item("minecraft:iron_chestplate", 1)));
        assertTrue(line.indexOf("iron_chestplate") < line.indexOf("wheat"), line);
    }
}
