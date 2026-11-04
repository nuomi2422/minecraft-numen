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
        Readiness r = Readiness.worstOf(List.of(Readiness.READY, Readiness.READY, Readiness.NOT_READY_STALE), Readiness.Transport.OK, Coverage.COMPLETE);
        assertEquals(Readiness.NOT_READY_STALE, r, "两条好的不能盖过一条过期的");
    }

    @Test
    void unknownBeatsEverything() {
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.worstOf(List.of(Readiness.NOT_READY_STALE, Readiness.NOT_READY_UNKNOWN), Readiness.Transport.OK, Coverage.COMPLETE));
    }

    @Test
    void partialIsWorseThanReadyButBetterThanPending() {
        assertEquals(Readiness.READY_PARTIAL,
                Readiness.worstOf(List.of(Readiness.READY, Readiness.READY_PARTIAL), Readiness.Transport.OK, Coverage.COMPLETE));
        assertEquals(Readiness.NOT_READY_PENDING,
                Readiness.worstOf(List.of(Readiness.READY_PARTIAL, Readiness.NOT_READY_PENDING), Readiness.Transport.OK, Coverage.COMPLETE));
    }

    @Test
    void emptyListIsReadyButCannotProveAbsenceByItself() {
        assertEquals(Readiness.READY, Readiness.worstOf(List.of(), Readiness.Transport.OK, Coverage.COMPLETE),
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
                Readiness.worstOf(java.util.Arrays.asList(Readiness.READY, null), Readiness.Transport.OK, Coverage.COMPLETE));
    }
    @Test
    void emptyListIsNotReadyWhenCoverageIsPartial() {
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.worstOf(List.of(), Readiness.Transport.OK, Coverage.PARTIAL),
                "空集合 + 部分覆盖 = 不可下结论（假完成最容易发生的地方）");
    }

    @Test
    void emptyListWithPendingTransportIsPending() {
        assertEquals(Readiness.NOT_READY_UNKNOWN,
                Readiness.worstOf(List.of(), Readiness.Transport.PENDING, Coverage.COMPLETE),
                "没采到就是没采到，不能因为'没有 item'就当可下结论");
    }

    @Test
    void anyWantsRefreshSurvivesMerging() {
        List<Readiness> items = List.of(Readiness.NOT_READY_PENDING, Readiness.NOT_READY_UNKNOWN, Readiness.READY);
        assertEquals(Readiness.NOT_READY_UNKNOWN, Readiness.worstOf(items, Readiness.Transport.OK, Coverage.COMPLETE));
        assertTrue(Readiness.anyWantsRefresh(items), "worstOf 吞掉的刷新信号要由 anyWantsRefresh 保留");
    }

    @Test
    void anyWantsRefreshFalseWhenNothingNeedsIt() {
        assertFalse(Readiness.anyWantsRefresh(List.of(Readiness.READY)),
                "只有完全可读才不需要刷新；READY_PARTIAL 本身就想刷新");
        assertTrue(Readiness.anyWantsRefresh(List.of(Readiness.READY, Readiness.READY_PARTIAL)),
                "部分覆盖也算'值得刷一次'");
        assertFalse(Readiness.anyWantsRefresh(List.of()));
        assertFalse(Readiness.anyWantsRefresh(null));
    }

    @Test
    void pendingOutranksStaleInReporting() {
        assertEquals(Readiness.NOT_READY_PENDING,
                Readiness.worstOf(List.of(Readiness.NOT_READY_STALE, Readiness.NOT_READY_PENDING),
                        Readiness.Transport.OK, Coverage.COMPLETE),
                "同时存在时先说'没采到'，比说'过期'更准确");
    }
}
