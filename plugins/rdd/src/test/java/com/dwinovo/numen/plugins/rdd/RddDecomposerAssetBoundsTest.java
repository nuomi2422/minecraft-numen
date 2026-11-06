package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.core.PlanningAssetSnapshot;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-06: lost/unknown 列表展示有界、确定性、不截半个 id；空/限内兼容。 */
class RddDecomposerAssetBoundsTest {

    private static AssetRegistry reg(List<String> ids) {
        AssetRegistry r = new AssetRegistry();
        for (String id : ids) {
            r.apply(new Observation("o-" + id, "inventory_scan", "test", "world", 1L, Map.of("count", 1)),
                    id, AssetScope.GLOBAL, null);
        }
        return r;
    }

    private static List<String> ids(String prefix, int n) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(prefix + "-" + i);
        return out;
    }

    @Test void withinLimitIsByteCompatible() {
        assertEquals("a、b、c", RddDecomposer.boundedIdList(List.of("a", "b", "c")));
    }

    @Test void overCountKeepsWholeIdsAndCountsOmission() {
        String t = RddDecomposer.boundedIdList(ids("asset", 30));
        assertTrue(t.endsWith("（其余 18 条略）"), t);
        for (String id : ids("asset", 30)) {
            if (t.contains(id + "、") || t.endsWith(id + "（其余 18 条略）")) continue;
            assertFalse(t.contains(id + "-"), "不允许出现半个 id: " + id);
        }
        assertTrue(t.split("、").length <= RddDecomposer.ID_LIST_MAX_IDS + 1);
    }

    @Test void charBoundKeepsWholeId() {
        String longId = "x".repeat(200);
        String t = RddDecomposer.boundedIdList(List.of(longId, longId + "b"));
        assertTrue(t.startsWith(longId), "第一个完整 id 必须在");
        assertTrue(t.endsWith("（其余 1 条略）"), t);
        assertFalse(t.contains(longId + "b"), "第二个放不下就不放，不能截半");
    }

    @Test void allObservedHasNoLostSection() {
        AssetRegistry r = reg(List.of("minecraft:bread"));
        PlanningAssetSnapshot snap = PlanningAssetSnapshot.from(Map.of("minecraft:bread", 3), r);
        String prompt = RddDecomposer.decompositionPrompt("目标", snap, List.of());
        assertFalse(prompt.contains("已失去"));
        assertTrue(prompt.contains("minecraft:bread"));
    }

    @Test void largeLostListIsBoundedInPromptWithOmission() {
        List<String> many = ids("minecraft:gem", 30);
        AssetRegistry r = reg(many);
        r.invalidateByType("inventory_scan");
        PlanningAssetSnapshot snap = PlanningAssetSnapshot.from(Map.of(), r);
        String prompt = RddDecomposer.decompositionPrompt("目标", snap, List.of());
        assertTrue(prompt.contains("已失去"));
        assertTrue(prompt.contains("条略）"), prompt.contains("条略）") ? "" : prompt);
        assertTrue(prompt.length() < 6000, "提示词不应被长列表拉爆");
    }
}
