package com.dwinovo.numen.rdd.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 2026-11-01 实机：base 观测分布在 6 个坐标上，按「最新一条」取会拿到空箱子。 */
class BaseSelectorTest {

    @Test
    void prefersTheOneAtRespawnEvenIfOlder() {
        var c = List.of(
                new BaseSelector.Candidate(371, 68, 715, 1000L),
                new BaseSelector.Candidate(370, 68, 715, 5000L));
        assertEquals(0, BaseSelector.pick(c, 371, 67, 716), "重生点在 371 附近 -> 选它，哪怕更旧");
    }

    @Test
    void fallsBackToNewestWhenRespawnIsUnknownOrFar() {
        var c = List.of(
                new BaseSelector.Candidate(0, 64, 0, 1000L),
                new BaseSelector.Candidate(500, 64, 500, 9000L));
        assertEquals(1, BaseSelector.pick(c, null, null, null));
        assertEquals(1, BaseSelector.pick(c, 100, 64, 100), "都不在半径内 -> 仍退回最新");
    }

    @Test
    void emptyCandidatesReturnMinusOne() {
        assertEquals(-1, BaseSelector.pick(List.of(), 1, 2, 3));
        assertEquals(-1, BaseSelector.pick(null, 1, 2, 3));
    }
}