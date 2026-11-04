package com.dwinovo.numen.worldstate.core.observation;

import com.dwinovo.numen.worldstate.core.model.Coverage;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 042 R2 / 审计 C.2：多条 item 的合并规则 + 证否的 response 级口径。 */
class ReadinessAggregationTest {

    @Test
    void worstOfIsNotMajority() {
        Readiness r = Readiness.worstOf(List.of(Readiness.READY, Readiness.READY, Readiness.NOT_READY_STALE));
        assertEquals(Readiness.NOT_READY_STALE, r, "两条好的不能盖过一条过期的");
    }

    @Test
    void unknownBeatsEverything() {
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.worstOf(List.of(Readiness.NOT_READY_STALE, Readiness.NOT_READY_UNKNOWN)));
    }

    @Test
    void partialIsWorseThanReadyButBetterThanPending() {
        assertEquals(Readiness.READY_PARTIAL,
                Readiness.worstOf(List.of(Readiness.READY, Readiness.READY_PARTIAL)));
        assertEquals(Readiness.NOT_READY_PENDING,
                Readiness.worstOf(List.of(Readiness.READY_PARTIAL, Readiness.NOT_READY_PENDING)));
    }

    @Test
    void emptyListIsReadyButCannotProveAbsenceByItself() {
        assertEquals(Readiness.READY, Readiness.worstOf(List.of()),
                "没有 item 要看，不是'不可核验'");
        assertTrue(Readiness.canProveAbsence(Readiness.Transport.OK, Coverage.COMPLETE),
                "证否要单独走 response 级");
    }

    @Test
    void absenceNeedsOkPlusComplete() {
        assertTrue(Readiness.canProveAbsence(Readiness.Transport.OK, Coverage.COMPLETE));
        assertFalse(Readiness.canProveAbsence(Readiness.Transport.OK, Coverage.PARTIAL));
        assertFalse(Readiness.canProveAbsence(Readiness.Transport.PARTIAL, Coverage.COMPLETE),
                "传输只成功一部分时，即使覆盖完整也不能说'没有'");
        assertFalse(Readiness.canProveAbsence(Readiness.Transport.PENDING, Coverage.COMPLETE));
        assertFalse(Readiness.canProveAbsence(Readiness.Transport.ERROR, Coverage.COMPLETE));
    }

    @Test
    void nullItemMakesWholeBatchUnknown() {
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.worstOf(java.util.Arrays.asList(Readiness.READY, null)));
    }
}
