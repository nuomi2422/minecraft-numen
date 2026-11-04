package com.dwinovo.numen.worldstate.core.observation;

import com.dwinovo.numen.worldstate.core.model.Coverage;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 042 R2 契约 1/2 的纯逻辑：OK≠可下结论、PARTIAL 不能证明不存在、stale/unknown 命中即不可核验。
 */
class ReadinessTest {

    private static final Set<String> REQUIRED = Set.of("count");
    private static final Set<String> KNOWN = Set.of("count");

    private static Readiness ok(Coverage coverage, Set<String> unknown, Set<String> stale) {
        return Readiness.assess(true, coverage, REQUIRED, KNOWN, unknown, stale);
    }

    @Test
    void okCompleteIsReady() {
        assertEquals(Readiness.READY, ok(Coverage.COMPLETE, Set.of(), Set.of()));
        assertTrue(ok(Coverage.COMPLETE, Set.of(), Set.of()).canProveAbsence());
    }

    @Test
    void okPartialCanReportButNotProveAbsence() {
        Readiness r = ok(Coverage.PARTIAL, Set.of(), Set.of());
        assertEquals(Readiness.READY_PARTIAL, r);
        assertTrue(r.canReportPresence(), "部分覆盖仍能说'有什么'");
        assertFalse(r.canProveAbsence(), "但绝不能据此说'没有'（039 §4）");
    }

    @Test
    void staleRequiredFieldIsNotReady() {
        assertEquals(Readiness.NOT_READY_STALE, ok(Coverage.COMPLETE, Set.of(), REQUIRED));
        assertFalse(ok(Coverage.COMPLETE, Set.of(), REQUIRED).canReportPresence());
    }

    @Test
    void unknownRequiredFieldIsNotReady() {
        assertEquals(Readiness.NOT_READY_UNKNOWN, ok(Coverage.COMPLETE, REQUIRED, Set.of()));
    }

    @Test
    void nonOkStatusIsNotReadyEvenWithFields() {
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.assess(false, Coverage.COMPLETE, REQUIRED, KNOWN, Set.of(), Set.of()));
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.assess(false, Coverage.COMPLETE, REQUIRED, KNOWN, Set.of(), Set.of()));
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.assess(false, Coverage.COMPLETE, REQUIRED, KNOWN, Set.of(), Set.of()));
    }

    @Test
    void unknownCoverageIsNotReady() {
        assertEquals(Readiness.NOT_READY_UNKNOWN, ok(Coverage.UNLOADED, Set.of(), Set.of()));
    }

    @Test
    void noRequiredFieldsMeansOnlyObservationExistenceMatters() {
        assertEquals(Readiness.READY,
                Readiness.assess(true, Coverage.COMPLETE, Set.of(), Set.of(), Set.of(), Set.of()));
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.assess(true, Coverage.UNLOADED, Set.of(), Set.of(), Set.of(), Set.of()));
    }

    @Test
    void staleTakesPriorityOverPartial() {
        assertEquals(Readiness.NOT_READY_STALE, ok(Coverage.PARTIAL, Set.of(), REQUIRED),
                "过期比'部分覆盖'更严重：先要求刷新");
    }

    @Test
    void nullSetsAreTolerated() {
        assertEquals(Readiness.READY,
                Readiness.assess(true, Coverage.COMPLETE, null, null, null, null));
    }

    @Test
    void unrelatedUnknownFieldsDoNotBlock() {
        Set<String> unknown = Set.of("someOtherField");
        assertEquals(Readiness.READY, ok(Coverage.COMPLETE, unknown, Set.of()),
                "只有'所需字段'才拦，别的字段缺不算");
    }
}