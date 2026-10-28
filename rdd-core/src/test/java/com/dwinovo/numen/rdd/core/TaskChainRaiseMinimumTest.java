package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 现场上调（{@link TaskChain#raiseCurrentMinimum}）：2026-10-28 主人要的
 * 「不知道村庄有多少干草块 → 先给个最低数，到了地方看清了再决定」。
 *
 * <p>钉死的三件事，每一条都对应一种会把项目烧掉的方向：
 * <ol>
 *   <li><b>只许上调</b>——下调会让硬判据当场变真（假完成）；</li>
 *   <li><b>只对 RUNNING</b>——PENDING 还没"现场"，已完成/暂停不许改历史；</li>
 *   <li><b>基线不动</b>——抬目标 ≠ 重开 attempt，否则 attempt 计数与净增量都会骗人。</li>
 * </ol>
 */
class TaskChainRaiseMinimumTest {

    private static TaskChain chain() {
        return new TaskChain(new Goal("g", "去村庄拿干草块", List.of(
                new PrimaryGoal("p1", "补给", List.of(
                        Subtask.hardCoded("p1-0", "拿干草块",
                                Map.of("asset_key", "minecraft:hay_block", "minimum", 3)),
                        Subtask.hardCoded("p1-1", "做面包",
                                Map.of("asset_key", "minecraft:bread", "minimum", 1)))),
                PrimaryGoal.unexpanded("p2", "石器时代", List.of()))));
    }

    @Test
    void raisingTargetOnTheSpotIsAllowedAndKeepsAttemptsAndBaseline() {
        TaskChain c = chain();
        c.startCurrent();                    // p1-0: PENDING → RUNNING
        long before = c.stateVersion();

        int old = c.raiseCurrentMinimum("p1-0", 12);

        assertEquals(3, old, "返回的是被替换掉的旧目标（回执要能写 3 → 12）");
        assertEquals(12, c.currentMinimum("p1-0"));
        assertTrue(c.stateVersion() > before, "判据变了 = 安全提交，stateVersion 必须 +1");
        assertEquals(1, c.attempts("p1-0"), "抬目标不是重开 attempt，尝试次数不许动");
        assertEquals("RUNNING", c.currentSubtaskStatus().name());
    }

    @Test
    void loweringOrKeepingTheSameTargetIsRefused() {
        TaskChain c = chain();
        c.startCurrent();

        assertThrows(IllegalArgumentException.class, () -> c.raiseCurrentMinimum("p1-0", 3),
                "同值不许（没有意义的提交会污染 stateVersion）");
        assertThrows(IllegalArgumentException.class, () -> c.raiseCurrentMinimum("p1-0", 2),
                "下调是危险方向：会让判据当场变真，必须拒绝并说明");
        assertEquals(3, c.currentMinimum("p1-0"), "被拒之后判据必须原样");
    }

    @Test
    void onlyTheRunningSubtaskCanBeRaised() {
        TaskChain c = chain();
        // 还没开跑（PENDING）
        assertThrows(IllegalStateException.class, () -> c.raiseCurrentMinimum("p1-0", 12),
                "PENDING 没有'现场'，改它属于规划（走 patch），不属于现场修正");

        c.startCurrent();
        assertThrows(IllegalArgumentException.class, () -> c.raiseCurrentMinimum("p1-1", 5),
                "不是当前节点：防张冠李戴（she 读到的是旧 id）");

        c.applyHardCodedResult("p1-0", true);   // 判过 → 推进到 p1-1
        IllegalArgumentException stale = assertThrows(IllegalArgumentException.class,
                () -> c.raiseCurrentMinimum("p1-0", 12));
        assertTrue(stale.getMessage().contains("stale"),
                "已完成节点不再是 current：拦在 stale id 这一层（改历史 = 改判据，必须不可能）");
    }

    @Test
    void acquiredBaselineIsNotRephotographed() {
        TaskChain c = new TaskChain(new Goal("g", "gather", List.of(
                new PrimaryGoal("p1", "采集", List.of(
                        Subtask.hardCoded("p1-0", "砍 3 原木",
                                Map.of("asset_key", "minecraft:oak_log", "minimum", 3, "mode", "acquire")))))));
        c.startCurrentWithCounts(Map.of("minecraft:oak_log", 5));
        assertEquals(Map.of("minecraft:oak_log", 5), c.acquireBaseline("p1-0"));

        c.raiseCurrentMinimum("p1-0", 10);

        assertEquals(Map.of("minecraft:oak_log", 5), c.acquireBaseline("p1-0"),
                "基线是'接活那一刻的量'；抬目标若连基线一起重拍，净增量会被重置（假完成/假未达成）");
    }

    @Test
    void raisedTargetSurvivesRestart() {
        TaskChain c = chain();
        c.startCurrent();
        c.raiseCurrentMinimum("p1-0", 12);

        TaskChain restored = TaskChain.fromJson(c.toJson());
        assertEquals(12, restored.currentMinimum("p1-0"), "目标数量必须活过重启，否则重启后她又按 3 判过");
        assertEquals("RUNNING", restored.currentSubtaskStatus().name());
    }
}
