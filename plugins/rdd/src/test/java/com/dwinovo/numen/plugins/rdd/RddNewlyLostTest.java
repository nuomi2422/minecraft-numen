package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「刚刚丢掉的需求键」判定（2026-10-23）。
 *
 * <p>背景：恢复支线的自然触发原来只看**整体**转移（空 → 非空）。实测只要链里有一个
 * **永远满足不了**的需求（当时的 bread 需 1 有 0），未满足集合就永远非空 ⇒ 触发点哑火。
 * 改成按**单个键**判断"上一拍还满足、这一拍缺了"，才符合"重要度确认那件东西没了"的原意。
 */
class RddNewlyLostTest {

    private static RddFactContext.Need need(String key, int min) {
        return new RddFactContext.Need(key, min, false);
    }

    @Test
    void satisfiedToUnmetIsLost() {
        RddFactContext.Watch prev = new RddFactContext.Watch("p1",
                Set.of("minecraft:bread"), Set.of("minecraft:bread", "minecraft:iron_ingot"));
        List<RddFactContext.Need> needs = List.of(need("minecraft:bread", 1), need("minecraft:iron_ingot", 3));

        Set<String> lost = RddFactContext.newlyLost(prev, needs, Set.of("minecraft:bread", "minecraft:iron_ingot"));
        assertEquals(Set.of("minecraft:iron_ingot"), lost, "只有铁锭是「刚刚丢的」；面包上一拍就缺着");
    }

    @Test
    void alreadyUnmetIsNotCountedAgain() {
        RddFactContext.Watch prev = new RddFactContext.Watch("p1",
                Set.of("minecraft:bread"), Set.of("minecraft:bread"));
        Set<String> lost = RddFactContext.newlyLost(prev, List.of(need("minecraft:bread", 1)),
                Set.of("minecraft:bread"));
        assertTrue(lost.isEmpty(), "一直是缺的，不算「刚刚丢」——否则每拍都会点名一次");
    }

    @Test
    void brandNewNeedIsNotALoss() {
        // 换阶段后需求集变了：新出现的需求不该被当成"丢了东西"（那会把恢复支线用在错误的地方）
        RddFactContext.Watch prev = new RddFactContext.Watch("p1", Set.of(), Set.of("minecraft:bread"));
        Set<String> lost = RddFactContext.newlyLost(prev, List.of(need("minecraft:diamond", 1)),
                Set.of("minecraft:diamond"));
        assertTrue(lost.isEmpty(), "上一拍根本没有这个需求 → 是「新要」，不是「丢」");
    }

    @Test
    void noPrevWatchMeansNoLoss() {
        assertTrue(RddFactContext.newlyLost(null, List.of(need("minecraft:bread", 1)),
                Set.of("minecraft:bread")).isEmpty(), "重启后第一拍没有上一状态可比，不许点名");
    }

    @Test
    void emptyUnmetMeansNothingLost() {
        RddFactContext.Watch prev = new RddFactContext.Watch("p1", Set.of(),
                Set.of("minecraft:bread"));
        assertTrue(RddFactContext.newlyLost(prev, List.of(need("minecraft:bread", 1)), Set.of()).isEmpty());
    }
}
