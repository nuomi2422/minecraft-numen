package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07 B1: one-source role views; no world reads, no invented fields. */
class RddRoleViewsTest {
    private static RddRoleViews.Facts facts() {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("task.current", "收小麦");
        v.put("self.hp", 19);
        v.put("self.food", 17);
        v.put("inventory.detail", Map.of("minecraft:wheat", 3, "minecraft:bread", 1));
        v.put("containers", "箱子 5 格");
        v.put("danger", "2 只敌怪");
        v.put("assets.summary", "铁甲 1");
        v.put("episode", "ep-1");
        v.put("outcome", "SUCCESS");
        return new RddRoleViews.Facts(v, Map.of("task", "rdd:chain=615c/rev=8", "inventory", "self:rev=9"));
    }

    @Test void planningKeepsHpFoodButDropsDetail() {
        String s = RddRoleViews.planning(facts()).text();
        assertTrue(s.contains("血量"), s);
        assertTrue(s.contains("饥饿"), s);
        assertFalse(s.contains("minecraft:wheat"), "规划不得带背包明细: " + s);
        assertFalse(s.contains("箱子 5 格"), "规划不得带容器内容: " + s);
        assertFalse(s.contains("2 只敌怪"), "规划不得带敌人列表: " + s);
    }

    @Test void reviewHasPostStateAndUnknownNotFalse() {
        String s = RddRoleViews.review(facts()).text();
        assertTrue(s.contains("post_state") && s.contains("UNKNOWN"), s);
        assertFalse(s.contains("changed=\"false\""), "未采集不得默认 false: " + s);
        Map<String, Object> v = new LinkedHashMap<>(facts().values());
        v.put("post_state", Map.of("target", "block:370,67,714", "changed", true, "sourceRef", "obs:123"));
        String with = RddRoleViews.review(new RddRoleViews.Facts(v, facts().sourceRefs())).text();
        assertTrue(with.contains("target=\"block:370,67,714\""), with);
        assertTrue(with.contains("changed=\"true\""), with);
    }

    @Test void externalDoesNotGrowPostStateOrWorldFields() {
        String s = RddRoleViews.external(facts()).text();
        assertFalse(s.contains("post_state"), "外部视图不得被 post_state 膨胀: " + s);
        assertFalse(s.contains("敌怪") || s.contains("血量"), s);
        assertTrue(s.contains("ep-1") && s.contains("SUCCESS"), s);
    }

    @Test void fourViewsShareTheSameTaskSourceRef() {
        var f = facts();
        assertEquals(RddRoleViews.planning(f).sourceRefs().get("task"),
                RddRoleViews.execution(f).sourceRefs().get("task"));
        assertEquals(RddRoleViews.review(f).sourceRefs().get("task"),
                RddRoleViews.external(f).sourceRefs().get("task"));
        assertEquals("rdd:chain=615c/rev=8", RddRoleViews.review(f).sourceRefs().get("task"));
    }

    @Test void planningInventoryFiveUnknownTriggers() {
        for (String key : new String[]{"self.current", "self.identified", "self.coverageFull"}) {
            Map<String, Object> v = new LinkedHashMap<>(facts().values());
            v.put("self.current", true); v.put("self.identified", true); v.put("self.coverageFull", true);
            v.put(key, false);
            assertTrue(RddRoleViews.planningInventory(new RddRoleViews.Facts(v, facts().sourceRefs())).contains("未知"), key);
        }
        Map<String, Object> truncated = new LinkedHashMap<>(facts().values());
        truncated.put("self.current", true); truncated.put("self.identified", true);
        truncated.put("self.coverageFull", true); truncated.put("self.truncated", true);
        assertTrue(RddRoleViews.planningInventory(new RddRoleViews.Facts(truncated, facts().sourceRefs())).contains("未知"));
        assertTrue(RddRoleViews.planningInventory(new RddRoleViews.Facts(Map.of(), Map.of())).contains("未知"));
        Map<String, Object> ok = new LinkedHashMap<>(facts().values());
        ok.put("self.current", true); ok.put("self.identified", true); ok.put("self.coverageFull", true);
        assertEquals("4", RddRoleViews.planningInventory(new RddRoleViews.Facts(ok, facts().sourceRefs())));
    }

    @Test void executionCountUsesDetailAndDoesNotMutateSource() {
        var f = facts();
        assertEquals("4", RddRoleViews.executionInventoryCount(f));
        assertEquals(Map.of("minecraft:wheat", 3, "minecraft:bread", 1), f.values().get("inventory.detail"));
        assertFalse(RddRoleViews.execution(f).text().isEmpty());
    }
}
