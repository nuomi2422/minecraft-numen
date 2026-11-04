package com.dwinovo.numen.worldstate.core.observation;

import com.dwinovo.numen.worldstate.core.model.Coverage;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 042 R2 契约 1/2：OK≠可下结论、PARTIAL 不能证明不存在、stale/unknown 命中即不可核验、
 * PENDING 与 ERROR 分开、**没传 requiredFields 是 fail-closed**。
 */
class ReadinessTest {

    private enum T { OK, PENDING, ERROR }

    private static final Set<String> REQUIRED = Set.of("count");
    private static final Set<String> KNOWN = Set.of("count");

    private static Readiness assess(Readiness.Transport t, Coverage c, Set<String> unknown, Set<String> stale) {
        return Readiness.assess(t, c, REQUIRED, KNOWN, unknown, stale);
    }

    private static Readiness ok(Coverage c) {
        return assess(Readiness.Transport.OK, c, Set.of(), Set.of());
    }

    @Test
    void okCompleteIsReady() {
        assertEquals(Readiness.READY, ok(Coverage.COMPLETE));
        assertTrue(ok(Coverage.COMPLETE).canProveAbsence());
        assertFalse(ok(Coverage.COMPLETE).wantsRefresh());
    }

    @Test
    void okPartialCanReportButNotProveAbsence() {
        Readiness r = ok(Coverage.PARTIAL);
        assertEquals(Readiness.READY_PARTIAL, r);
        assertTrue(r.canReportPresence(), "部分覆盖仍能说'有什么'");
        assertFalse(r.canProveAbsence(), "但绝不能据此说'没有'（039 §4）");
    }

    @Test
    void staleRequiredFieldNeedsRefresh() {
        Readiness r = assess(Readiness.Transport.OK, Coverage.COMPLETE, Set.of(), REQUIRED);
        assertEquals(Readiness.NOT_READY_STALE, r);
        assertFalse(r.canReportPresence());
        assertTrue(r.wantsRefresh(), "过期要触发显式 refresh");
    }

    @Test
    void unknownRequiredFieldIsNotReady() {
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                assess(Readiness.Transport.OK, Coverage.COMPLETE, REQUIRED, Set.of()));
    }

    @Test
    void pendingIsItsOwnState() {
        Readiness r = Readiness.assess(Readiness.Transport.PENDING, Coverage.COMPLETE, REQUIRED, KNOWN, Set.of(), Set.of());
        assertEquals(Readiness.NOT_READY_PENDING, r, "没观测 ≠ 失败：一个该刷新，一个该报错");
        assertTrue(r.wantsRefresh());
    }

    @Test
    void errorIsUnknownNotPending() {
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.assess(Readiness.Transport.ERROR, Coverage.COMPLETE, REQUIRED, KNOWN, Set.of(), Set.of()));
    }

    @Test
    void unloadedCoverageIsNotReady() {
        assertEquals(Readiness.NOT_READY_UNKNOWN, ok(Coverage.UNLOADED));
    }

    @Test
    void nullRequiredFieldsIsFailClosed() {
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.assess(Readiness.Transport.OK, Coverage.COMPLETE, null, Set.of(), Set.of(), Set.of()),
                "忘了传 requiredFields 不等于'不需要字段'——必须 fail-closed");
        assertEquals(Readiness.READY,
                Readiness.assess(Readiness.Transport.OK, Coverage.COMPLETE, Set.of(), Set.of(), Set.of(), Set.of()),
                "只有显式空集才代表'我确认不需要字段'");
    }

    @Test
    void staleTakesPriorityOverPartial() {
        Readiness r = assess(Readiness.Transport.OK, Coverage.PARTIAL, Set.of(), REQUIRED);
        assertEquals(Readiness.NOT_READY_STALE, r, "过期比'部分覆盖'更严重：先要求刷新");
    }

    @Test
    void unrelatedUnknownFieldsDoNotBlock() {
        assertEquals(Readiness.READY, assess(Readiness.Transport.OK, Coverage.COMPLETE, Set.of("other"), Set.of()),
                "只有'所需字段'才拦");
    }
}
