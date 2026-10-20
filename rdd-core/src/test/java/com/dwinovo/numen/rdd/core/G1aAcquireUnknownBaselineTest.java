package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.Subtask;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G1a · S35 先红后绿：空基线（现状 = startCurrentWithCounts(null) 折成的空表）
 * 下 acquire 的假阳性反例 —— 75号 §16.2-2。
 *
 * <p>本测试**故意先在修复前跑**：证明缺陷存在（红），修复后转绿。
 * 缺陷形状：{@code RddSubmitTool} 拿不到背包 → catch → 传 null →
 * {@code captureAcquireBaseline(null)} 折成空表 → {@code acquireBaseline()} 返回空表 ≠ null
 * → {@code HardCodedEvaluator} 的 null 守卫失效 → {@code gained = have − 0 = have}
 * → 背包已有 5、minimum=3 → 5≥3 **假通过**。
 */
class G1aAcquireUnknownBaselineTest {

    private static Map<String, Object> acquireCondition(String key, int minimum) {
        Map<String, Object> c = new HashMap<>();
        c.put("asset_key", key);
        c.put("minimum", minimum);
        c.put("mode", "acquire");
        return c;
    }

    private static TaskChain chainWithAcquireSubtask(String key, int minimum) {
        var subtask = Subtask.hardCoded("st-1", "dig " + key + " x" + minimum,
                acquireCondition(key, minimum));
        var primary = new com.dwinovo.numen.rdd.api.PrimaryGoal(
                "p-1", "p", List.of(subtask));
        var goal = new com.dwinovo.numen.rdd.api.Goal("g-1", "g", List.of(primary));
        return new TaskChain(goal, java.util.Set.of());
    }

    /**
     * S35（修复后转绿）：「观测失败」的 UNKNOWN 基线 + 背包已有 5 → minimum=3 → **不得通过**。
     *
     * <p>先红阶段版本（assertTrue 证明缺陷）已归档于批次卡；本断言是修复后的正确期望。
     * 修复机制：{@code acquireBaseline()} 对 unobserved 条目返回 null → null 守卫接住 → false。
     */
    @Test
    void s35_unknownBaselineWithExistingStockMustNotPass() {
        TaskChain chain = chainWithAcquireSubtask("minecraft:dirt", 3);
        // 宿主拿不到背包（RddSubmitTool 的 catch 路径）→ null
        chain.startCurrentWithCounts(null);
        Map<String, Integer> baseline = chain.acquireBaseline("st-1");

        // 修复后：UNKNOWN 折回 null（不是空表），守卫接住
        assertNull(baseline, "G1a 修复后：观测失败的基线读数为 null（UNKNOWN≠真空），守卫能接住");
        assertTrue(chain.isBaselineUnobserved("st-1"), "读数口确认是观测失败");

        // 缺陷断言（修复后必须 false）：
        assertFalse(HardCodedEvaluator.matches(
                acquireCondition("minecraft:dirt", 3),
                Map.of("minecraft:dirt", 5),   // 背包已有 5（任务没干活，已有量不算新增）
                baseline), "【G1a 绿】UNKNOWN 基线 + 已有 5 + min 3 → 不通过（假阳性已修）");
    }

    /**
     * S36：确认空背包（observed=true + {}）是合法基线 —— gained = have − 0 = have。
     * 修复不许把这一档错杀。
     */
    @Test
    void s36_trueEmptyBaselineIsLegitimate() {
        TaskChain chain = chainWithAcquireSubtask("minecraft:dirt", 3);
        // 真空背包（读到空表，观测成功）
        chain.startCurrentWithCounts(Map.of());
        Map<String, Integer> baseline = chain.acquireBaseline("st-1");

        // 真空基线下：挖 5 个 → gained = 5 − 0 = 5 ≥ 3 → true（合法）
        assertTrue(HardCodedEvaluator.matches(
                acquireCondition("minecraft:dirt", 3),
                Map.of("minecraft:dirt", 5),
                baseline), "确认空仓的 acquire 必须保持合法（gained=have−0）");

        // 真空基线下：只有 2 个 → 2 − 0 < 3 → false
        assertFalse(HardCodedEvaluator.matches(
                acquireCondition("minecraft:dirt", 3),
                Map.of("minecraft:dirt", 2),
                baseline), "不够就是不够");
    }

    /**
     * S37：正常基线回归 —— 既有行为不许被修复改变。
     */
    @Test
    void s37_normalBaselineUnchanged() {
        TaskChain chain = chainWithAcquireSubtask("minecraft:dirt", 3);
        chain.startCurrentWithCounts(Map.of("minecraft:dirt", 3));
        Map<String, Integer> baseline = chain.acquireBaseline("st-1");

        assertTrue(HardCodedEvaluator.matches(
                acquireCondition("minecraft:dirt", 3),
                Map.of("minecraft:dirt", 6),    // 6 − 3 = 3 ≥ 3
                baseline), "净增 3 刚好达标 → 通过");
        assertFalse(HardCodedEvaluator.matches(
                acquireCondition("minecraft:dirt", 3),
                Map.of("minecraft:dirt", 5),    // 5 − 3 = 2 < 3
                baseline), "净增 2 不够 → 不通过");
    }

    /**
     * S36/S37 之间的边界：修复后观测失败（null）与真空（空表）**必须可区分**。
     */
    @Test
    void s35_currentNullAndTrueEmptyAreIndistinguishable() {
        TaskChain a = chainWithAcquireSubtask("minecraft:dirt", 3);
        a.startCurrentWithCounts(null);          // 观测失败
        TaskChain b = chainWithAcquireSubtask("minecraft:dirt", 3);
        b.startCurrentWithCounts(Map.of());      // 真空

        assertNull(a.acquireBaseline("st-1"), "观测失败 → null（UNKNOWN）");
        assertNotNull(b.acquireBaseline("st-1"), "真空调用返回空表（合法基线）—— 与观测失败区分开");
        assertTrue(b.acquireBaseline("st-1").isEmpty());
        assertFalse(b.isBaselineUnobserved("st-1"), "真空不是观测失败");
        assertTrue(a.isBaselineUnobserved("st-1"), "null 是观测失败");
    }
}
