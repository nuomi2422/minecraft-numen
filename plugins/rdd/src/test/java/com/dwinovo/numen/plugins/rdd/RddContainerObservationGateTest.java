package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.WorldObservationService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07：FULL+空→空表可当真；PARTIAL→不可（走直读兜底）；ERROR/PENDING→不可。 */
class RddContainerObservationGateTest {
    @Test void fullEmptyTrusted() {
        assertTrue(RddContainerObservationGate.canTrustEmpty(
                WorldObservationService.Status.OK, WorldObservationService.Coverage.FULL));
    }
    @Test void partialNotTrusted() {
        assertFalse(RddContainerObservationGate.canTrustEmpty(
                WorldObservationService.Status.PARTIAL, WorldObservationService.Coverage.PARTIAL));
        assertFalse(RddContainerObservationGate.canTrustEmpty(
                WorldObservationService.Status.OK, WorldObservationService.Coverage.PARTIAL));
    }
    @Test void errorPendingNotTrusted() {
        assertFalse(RddContainerObservationGate.canTrustEmpty(
                WorldObservationService.Status.ERROR, WorldObservationService.Coverage.FULL));
        assertFalse(RddContainerObservationGate.canTrustEmpty(
                WorldObservationService.Status.PENDING, WorldObservationService.Coverage.UNKNOWN));
    }
}
