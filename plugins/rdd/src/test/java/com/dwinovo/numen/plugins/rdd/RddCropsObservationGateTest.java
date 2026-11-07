package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.ObservationMetadata;
import com.dwinovo.numen.api.observation.WorldObservationService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07：PARTIAL/过期/截断不可当“没有”；FULL+CURRENT 才正常。 */
class RddCropsObservationGateTest {
    @Test void fullCurrentNotTruncatedIsComplete() {
        assertTrue(RddCropsObservationGate.complete(WorldObservationService.Status.OK,
                WorldObservationService.Coverage.FULL, ObservationMetadata.Freshness.CURRENT, 3, 64));
    }
    @Test void partialOrStaleOrTruncatedNotComplete() {
        assertFalse(RddCropsObservationGate.complete(WorldObservationService.Status.PARTIAL,
                WorldObservationService.Coverage.PARTIAL, ObservationMetadata.Freshness.CURRENT, 3, 64));
        assertFalse(RddCropsObservationGate.complete(WorldObservationService.Status.OK,
                WorldObservationService.Coverage.FULL, ObservationMetadata.Freshness.STALE, 3, 64));
        assertFalse(RddCropsObservationGate.complete(WorldObservationService.Status.OK,
                WorldObservationService.Coverage.FULL, ObservationMetadata.Freshness.CURRENT, 64, 64));
    }
}
