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
    @Test void bothListsShareStrictBudgetIncludingMarkers() {
        List<String> lost = ids("minecraft:gem", 30);
        List<String> unknown = ids("minecraft:ore", 30);
        AssetRegistry r = reg(lost);
        AssetRegistry r2 = reg(unknown);
        r2.markUnknown("minecraft:ore-0");
        for (int i = 0; i < 30; i++) r2.markUnknown("minecraft:ore-" + i);
        PlanningAssetSnapshot snap = PlanningAssetSnapshot.from(Map.of(), r);
        int reserve = RddDecomposer.marker(30).length();
        String lostText = RddDecomposer.boundedIdList(lost, RddDecomposer.ID_LIST_MAX_CHARS, reserve);
        String unknownText = RddDecomposer.boundedIdList(unknown, RddDecomposer.ID_LIST_MAX_CHARS - lostText.length(), 0);
        assertTrue(lostText.length() + unknownText.length() <= RddDecomposer.ID_LIST_MAX_CHARS,
                "两列表+标记必须 ≤240：" + lostText.length() + "+" + unknownText.length());
    }

    @Test void exactlyAtBudgetAndZeroRemainingAreStrict() {
        String id = "a".repeat(200);
        String t1 = RddDecomposer.boundedIdList(List.of(id, id + "b"), RddDecomposer.ID_LIST_MAX_CHARS, RddDecomposer.marker(1).length());
        assertTrue(t1.length() <= RddDecomposer.ID_LIST_MAX_CHARS);
        assertEquals("", RddDecomposer.boundedIdList(List.of(id), 5, 0), "连省略标记都放不下时返回空，不越界");
        assertEquals(RddDecomposer.marker(1), RddDecomposer.boundedIdList(List.of(id), 10, 0), "只有标记放得下时只留标记");
    }

    @Test void perListIdCapIsTwelve() {
        List<String> many = ids("x", 30);
        String t = RddDecomposer.boundedIdList(many, 10_000, 0);
        long commas = t.chars().filter(c -> c == '、').count();
        assertTrue(commas + 1 <= RddDecomposer.ID_LIST_MAX_IDS, "每列表最多 12 条: " + t);
    }

}
