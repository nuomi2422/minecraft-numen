package com.dwinovo.numen.plugins.learner.sample;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 158 §8 的样本生命周期：{@code OPEN → READY_FOR_REVIEW → IN_REVIEW → RESOLVED → ARCHIVED}，
 * 外加 {@code DEFERRED / INVALID / DUPLICATE}。
 *
 * <p>钉死：延迟能回来、归档是终态、自己到自己算非法（重复写状态多半是并发 bug）、
 * 三个终点不是同一个东西。
 */
class SampleStateTest {

    @Test
    void 主链每一步都放行() {
        assertTrue(SampleState.OPEN.canMoveTo(SampleState.READY_FOR_REVIEW));
        assertTrue(SampleState.READY_FOR_REVIEW.canMoveTo(SampleState.IN_REVIEW));
        assertTrue(SampleState.IN_REVIEW.canMoveTo(SampleState.RESOLVED));
        assertTrue(SampleState.RESOLVED.canMoveTo(SampleState.ARCHIVED));
    }

    @Test
    void 不许跳步或倒退() {
        assertFalse(SampleState.OPEN.canMoveTo(SampleState.IN_REVIEW), "没进复盘窗口不能被取走");
        assertFalse(SampleState.OPEN.canMoveTo(SampleState.RESOLVED), "没复盘不能直接出结论");
        assertFalse(SampleState.RESOLVED.canMoveTo(SampleState.OPEN), "结论不能悄悄作废");
        assertFalse(SampleState.IN_REVIEW.canMoveTo(SampleState.READY_FOR_REVIEW), "没有回退边");
    }

    @Test
    void 自己到自己也算非法() {
        for (SampleState s : SampleState.values()) {
            assertFalse(s.canMoveTo(s), s + " 自迁移必须被拒");
        }
    }

    @Test
    void 延迟必须能回来() {
        assertTrue(SampleState.DEFERRED.canMoveTo(SampleState.READY_FOR_REVIEW),
                "一次预算不足不许把真实失败永久埋掉");
        assertTrue(SampleState.IN_REVIEW.canMoveTo(SampleState.DEFERRED));
    }

    @Test
    void 延迟不能退回未入队状态() {
        // ⚠ 变异测试 M7 逼出来的：原用例只钉了「延迟能回来」，没钉「不许退回 OPEN」。
        //   退回 OPEN 等于抹掉「已经推迟过一次」这件事，调度会重新算一次数量阈值。
        assertFalse(SampleState.DEFERRED.canMoveTo(SampleState.OPEN));
    }

    @Test
    void 疑似重复必须带指向否则构造失败() {
        // ⚠ 变异测试 M8 逼出来的：守卫写了但没人测，删掉它测试照样绿。
        assertThrows(IllegalArgumentException.class, () -> new LearningSample(
                "s-1", "run", "c1", "g1", "1", "death", "d", "z", "w1", "overworld",
                0L, "fp", "wk", SampleState.OPEN, "instrumentation",
                1L, 1L, 1L, true, "", java.util.List.of(), java.util.List.of()));
    }

    @Test
    void 三个终点语义不同且都是终态() {
        assertTrue(SampleState.ARCHIVED.terminal());
        assertTrue(SampleState.INVALID.terminal());
        assertTrue(SampleState.DUPLICATE.terminal());
        assertFalse(SampleState.RESOLVED.terminal(), "RESOLVED 还得能归档");
        assertFalse(SampleState.ARCHIVED.canMoveTo(SampleState.OPEN));
    }

    @Test
    void 只有可复盘状态才算待办() {
        assertTrue(SampleState.OPEN.reviewable());
        assertTrue(SampleState.READY_FOR_REVIEW.reviewable());
        assertTrue(SampleState.DEFERRED.reviewable());
        assertFalse(SampleState.IN_REVIEW.reviewable());
        assertFalse(SampleState.RESOLVED.reviewable());
        assertFalse(SampleState.ARCHIVED.reviewable());
    }

    @Test
    void 非法迁移在样本对象上会抛() {
        SampleOccurrence occ = new SampleOccurrence("ev-1", 1L, "instrumentation");
        LearningSample s = LearningSample.create("s-1", "c1", "death", "d", "z",
                "run", "g1", "1", "w1", "overworld", 0L, "fp", "wk", "instrumentation", 1L, occ);
        assertThrows(IllegalStateException.class, () -> s.withState(SampleState.RESOLVED),
                "OPEN 不能直接 RESOLVED");
        assertTrue(s.withState(SampleState.READY_FOR_REVIEW).state() == SampleState.READY_FOR_REVIEW);
    }
}
