package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.Subtask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D20-C2（75号 15.4 基线可见性）：TaskChain.snapshot() 必须能区分
 * 「acquire 基线从未捕获」和「捕获了但当时背包是空的」。
 *
 * <p>两个状态在判定层等价（HardCodedEvaluator 都判未达成——那是对的，
 * 「宁不满足不假完成」），但在观测/排障上完全不同：
 * <ul>
 *   <li>{@code captured=false}：宿主没走到 capture 那步（提交路径漏了？）</li>
 *   <li>{@code captured=true, empty=true}：捕获时机对，但当时背包真的空 ——
 *       gain = have − 0 = have，任何获取都会立刻计入 —— 行为正确，别乱修</li>
 *   <li>{@code captured=true, keys=[...]}}：正常基线</li>
 * </ul>
 */
class D20C2BaselineVisibilityTest {

    private static TaskChain chainWithAcquireSubtask() {
        var condition = new java.util.LinkedHashMap<String, Object>();
        condition.put("asset_key", "minecraft:dirt");
        condition.put("minimum", 3);
        condition.put("mode", "acquire");
        Subtask subtask = Subtask.hardCoded("st-1", "dig", condition);
        var primary = new com.dwinovo.numen.rdd.api.PrimaryGoal(
                "p-1", "p", List.of(subtask));
        var goal = new com.dwinovo.numen.rdd.api.Goal("g-1", "g", List.of(primary));
        return new TaskChain(goal, java.util.Set.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void uncapturedBaselineIsVisibleAsFalse() {
        TaskChain chain = chainWithAcquireSubtask();
        chain.startCurrent();
        Map<String, Object> snap = chain.snapshot();
        List<Map<String, Object>> primaries = (List<Map<String, Object>>) snap.get("primaries");
        Map<String, Object> st = primaries.get(0).get("subtasks") instanceof List<?> l
                ? (Map<String, Object>) ((List<?>) primaries.get(0).get("subtasks")).get(0) : null;
        assertNotNull(st);
        assertEquals(Boolean.FALSE, st.get("acquireBaselineCaptured"),
                "未捕获必须显式可见为 false（不是缺键——缺键等于「不知道」，这里明明知道）");
    }

    @Test
    @SuppressWarnings("unchecked")
    void capturedEmptyBaselineIsDistinguishable() {
        TaskChain chain = chainWithAcquireSubtask();
        // ★ G1a 之后：counts=null 是观测失败（UNKNOWN），counts=Map.of() 是确认真空。
        // 本测试改用 Map.of() ——「捕获了且当时背包真空」的合法档位。
        chain.startCurrentWithCounts(Map.of());
        Map<String, Object> snap = chain.snapshot();
        List<Map<String, Object>> primaries = (List<Map<String, Object>>) snap.get("primaries");
        Map<String, Object> st = (Map<String, Object>) ((List<?>) primaries.get(0).get("subtasks")).get(0);
        assertEquals(Boolean.TRUE, st.get("acquireBaselineCaptured"), "捕获过");
        assertEquals(Boolean.TRUE, st.get("acquireBaselineEmpty"), "真空背包的空表显式标记");
        assertFalse(st.containsKey("acquireBaselineKeys"), "空表不该有 keys 键");
        assertFalse(chain.isBaselineUnobserved(chain.currentSubtask().id()), "Map.of() 不是观测失败");
    }

    @Test
    @SuppressWarnings("unchecked")
    void capturedBaselineExposesKeys() {
        TaskChain chain = chainWithAcquireSubtask();
        chain.startCurrentWithCounts(Map.of("minecraft:dirt", 10, "minecraft:oak_log", 2));
        Map<String, Object> snap = chain.snapshot();
        List<Map<String, Object>> primaries = (List<Map<String, Object>>) snap.get("primaries");
        Map<String, Object> st = (Map<String, Object>) ((List<?>) primaries.get(0).get("subtasks")).get(0);
        assertEquals(Boolean.TRUE, st.get("acquireBaselineCaptured"));
        var keys = (List<?>) st.get("acquireBaselineKeys");
        assertNotNull(keys);
        assertTrue(keys.contains("minecraft:dirt"), "基线键可见");
        assertTrue(keys.contains("minecraft:oak_log"));
        assertFalse(st.containsKey("acquireBaselineEmpty"));
    }
}
