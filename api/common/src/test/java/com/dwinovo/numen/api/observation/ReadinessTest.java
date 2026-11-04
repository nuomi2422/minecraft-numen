package com.dwinovo.numen.api.observation;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 042 R2 契约 1/2：OK≠可下结论、PARTIAL 不能证明不存在、stale/unknown 命中即不可核验、
 * PENDING 与 ERROR 分开、没传 requiredFields 是 fail-closed、合并取最坏、证否走 response 级。
 *
 * <p>这里直接用 API 的枚举值（不写字符串）——枚举加值/改名时编译期就会红。
 */
class ReadinessTest {

    private static final Set<String> REQ = Set.of("count");
    private static final Set<String> KNOWN = Set.of("count");

    private static Readiness assess(WorldObservationService.Status s,
                                    WorldObservationService.Coverage c,
                                    Set<String> unknown, Set<String> stale) {
        return Readiness.assess(s, c, REQ, KNOWN, unknown, stale);
    }

    @Test
    void enumSizesArePinned() {
        assertEquals(4, WorldObservationService.Status.values().length,
                "Status 加值就要回来更新 Readiness 的语义");
        assertEquals(3, WorldObservationService.Coverage.values().length,
                "Coverage 加值就要回来更新 Readiness 的语义");
    }

    @Test
    void okFullIsReady() {
        Readiness r = assess(WorldObservationService.Status.OK, WorldObservationService.Coverage.FULL, Set.of(), Set.of());
        assertEquals(Readiness.READY, r);
        assertTrue(r.canProveAbsence());
        assertFalse(r.wantsRefresh());
    }

    @Test
    void partialCoverageCanReportButNotProveAbsence() {
        Readiness r = assess(WorldObservationService.Status.OK, WorldObservationService.Coverage.PARTIAL, Set.of(), Set.of());
        assertEquals(Readiness.READY_PARTIAL, r);
        assertTrue(r.canReportPresence());
        assertFalse(r.canProveAbsence(), "部分覆盖绝不能据此说'没有'（039 §4）");
    }

    @Test
    void staleRequiredFieldNeedsRefresh() {
        Readiness r = assess(WorldObservationService.Status.OK, WorldObservationService.Coverage.FULL, Set.of(), REQ);
        assertEquals(Readiness.NOT_READY_STALE, r);
        assertFalse(r.canReportPresence());
        assertTrue(r.wantsRefresh());
    }

    @Test
    void unknownRequiredFieldIsNotReady() {
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                assess(WorldObservationService.Status.OK, WorldObservationService.Coverage.FULL, REQ, Set.of()));
    }

    @Test
    void pendingIsItsOwnState() {
        Readiness r = assess(WorldObservationService.Status.PENDING, WorldObservationService.Coverage.FULL, Set.of(), Set.of());
        assertEquals(Readiness.NOT_READY_PENDING, r, "没观测 ≠ 失败：一个该刷新，一个该报错");
        assertTrue(r.wantsRefresh());
    }

    @Test
    void errorIsUnknownNotPending() {
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                assess(WorldObservationService.Status.ERROR, WorldObservationService.Coverage.FULL, Set.of(), Set.of()));
    }

    @Test
    void unknownCoverageIsNotReady() {
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                assess(WorldObservationService.Status.OK, WorldObservationService.Coverage.UNKNOWN, Set.of(), Set.of()));
    }

    @Test
    void nullRequiredFieldsIsFailClosed() {
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.assess(WorldObservationService.Status.OK, WorldObservationService.Coverage.FULL,
                        null, Set.of(), Set.of(), Set.of()),
                "忘了传 requiredFields 不等于'不需要字段'");
        assertEquals(Readiness.READY,
                Readiness.assess(WorldObservationService.Status.OK, WorldObservationService.Coverage.FULL,
                        Set.of(), Set.of(), Set.of(), Set.of()),
                "只有显式空集才代表'我确认不需要字段'");
    }

    @Test
    void statusPartialNeverClaimsAbsence() {
        Readiness r = assess(WorldObservationService.Status.PARTIAL, WorldObservationService.Coverage.FULL, Set.of(), Set.of());
        assertEquals(Readiness.READY_PARTIAL, r, "传输只成功一部分，即使覆盖 FULL 也不能说'没有'");
        assertTrue(r.canReportPresence());
    }

    @Test
    void unrelatedUnknownFieldsDoNotBlock() {
        assertEquals(Readiness.READY,
                assess(WorldObservationService.Status.OK, WorldObservationService.Coverage.FULL, Set.of("other"), Set.of()));
    }

    @Test
    void worstOfIsNotMajority() {
        assertEquals(Readiness.NOT_READY_STALE,
                Readiness.worstOf(List.of(Readiness.READY, Readiness.READY, Readiness.NOT_READY_STALE),
                        WorldObservationService.Status.OK, WorldObservationService.Coverage.FULL));
    }

    @Test
    void worstOfEmptyListIsDecidedByStatusAndCoverage() {
        assertEquals(Readiness.READY,
                Readiness.worstOf(List.of(), WorldObservationService.Status.OK, WorldObservationService.Coverage.FULL));
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.worstOf(List.of(), WorldObservationService.Status.OK, WorldObservationService.Coverage.PARTIAL),
                "空集合 + 部分覆盖 = 不可下结论（假完成高发处）");
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.worstOf(List.of(), WorldObservationService.Status.PENDING, WorldObservationService.Coverage.FULL));
    }

    @Test
    void anyWantsRefreshSurvivesMerging() {
        List<Readiness> items = List.of(Readiness.NOT_READY_PENDING, Readiness.NOT_READY_UNKNOWN, Readiness.READY);
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.worstOf(items, WorldObservationService.Status.OK, WorldObservationService.Coverage.FULL));
        assertTrue(Readiness.anyWantsRefresh(items), "worstOf 吞掉的刷新信号要由 anyWantsRefresh 保留");
        assertFalse(Readiness.anyWantsRefresh(List.of(Readiness.READY)));
        assertFalse(Readiness.anyWantsRefresh(null));
    }

    @Test
    void absenceNeedsOkPlusFull() {
        assertTrue(Readiness.canProveAbsence(WorldObservationService.Status.OK, WorldObservationService.Coverage.FULL));
        assertFalse(Readiness.canProveAbsence(WorldObservationService.Status.OK, WorldObservationService.Coverage.PARTIAL));
        assertFalse(Readiness.canProveAbsence(WorldObservationService.Status.PARTIAL, WorldObservationService.Coverage.FULL));
        assertFalse(Readiness.canProveAbsence(WorldObservationService.Status.PENDING, WorldObservationService.Coverage.FULL));
        assertFalse(Readiness.canProveAbsence(WorldObservationService.Status.ERROR, WorldObservationService.Coverage.FULL));
    }

    @Test
    void pendingOutranksStaleInReporting() {
        assertEquals(Readiness.NOT_READY_PENDING,
                Readiness.worstOf(List.of(Readiness.NOT_READY_STALE, Readiness.NOT_READY_PENDING),
                        WorldObservationService.Status.OK, WorldObservationService.Coverage.FULL),
                "同时存在时先说'没采到'，比说'过期'更准确");
    }

    @Test
    void nullItemMakesWholeBatchUnknown() {
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.worstOf(java.util.Arrays.asList(Readiness.READY, null),
                        WorldObservationService.Status.OK, WorldObservationService.Coverage.FULL));
    }
}
