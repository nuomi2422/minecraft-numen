package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.WorldObservationService.Coverage;
import com.dwinovo.numen.api.observation.WorldObservationService.Status;
import com.dwinovo.numen.worldstate.core.model.Freshness;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 039 §4 的四类语义：这条观测能不能当事实用，全看这里。
 *
 * <p>重点是"不能把读不到当没有"：没观测=PENDING，覆盖不全/过期=最多 PARTIAL。
 */
class ObservationMapTest {

    private static final com.dwinovo.numen.worldstate.core.model.Coverage COMPLETE =
            com.dwinovo.numen.worldstate.core.model.Coverage.COMPLETE;
    private static final com.dwinovo.numen.worldstate.core.model.Coverage PARTIAL =
            com.dwinovo.numen.worldstate.core.model.Coverage.PARTIAL;
    private static final com.dwinovo.numen.worldstate.core.model.Coverage UNLOADED =
            com.dwinovo.numen.worldstate.core.model.Coverage.UNLOADED;

    @Test
    void completeAndCurrentIsTheOnlyOk() {
        var m = ObservationMap.map(COMPLETE, Freshness.CURRENT, true);
        assertEquals(Status.OK, m.status());
        assertEquals(Coverage.FULL, m.coverage());
        assertFalse(m.stale());
        assertNull(m.reason(), "OK 不该带原因");
    }

    @Test
    void partialCoverageCanNeverBeOk() {
        var m = ObservationMap.map(PARTIAL, Freshness.CURRENT, true);
        assertEquals(Status.PARTIAL, m.status(), "覆盖不全不许说 OK");
        assertEquals(Coverage.PARTIAL, m.coverage());
        assertTrue(m.reason().contains("coverage"), m.reason());
    }

    @Test
    void unloadedIsPartialWithUnknownCoverage() {
        var m = ObservationMap.map(UNLOADED, Freshness.CURRENT, true);
        assertEquals(Status.PARTIAL, m.status());
        assertEquals(Coverage.UNKNOWN, m.coverage());
    }

    @Test
    void staleAndHistoricalAreDowngraded() {
        for (Freshness f : new Freshness[]{Freshness.STALE, Freshness.HISTORICAL}) {
            var m = ObservationMap.map(COMPLETE, f, true);
            assertEquals(Status.PARTIAL, m.status(), f + " 不许冒充当前事实");
            assertTrue(m.stale());
            assertTrue(m.reason().contains(f.name()), m.reason());
        }
    }

    @Test
    void missingObservationIsPendingNotEmptyWorld() {
        var m = ObservationMap.map(null, null, false);
        assertEquals(Status.PENDING, m.status(), "没观测=待刷新，不是'世界是空的'");
        assertEquals(Coverage.UNKNOWN, m.coverage());
        assertFalse(m.stale());
    }

    @Test
    void nullCoverageDegradesToUnknownRatherThanCrash() {
        var m = ObservationMap.map(null, Freshness.CURRENT, true);
        assertEquals(Coverage.UNKNOWN, m.coverage());
        assertEquals(Status.PARTIAL, m.status());
    }
}
