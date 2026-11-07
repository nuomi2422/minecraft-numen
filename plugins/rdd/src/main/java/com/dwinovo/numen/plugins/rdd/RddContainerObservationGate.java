package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.Readiness;
import com.dwinovo.numen.api.observation.WorldObservationService;

/** 2026-11-07 P0b：观测空表能否当“已知为空”的纯判据（不含 MC 依赖，可单测）。 */
final class RddContainerObservationGate {
    private RddContainerObservationGate() {}

    /** 只有能证明“没有”（OK+FULL）才允许把空表当结论；PARTIAL/ERROR/PENDING 一律不可。 */
    static boolean canTrustEmpty(WorldObservationService.Status status,
                                 WorldObservationService.Coverage coverage) {
        return Readiness.canProveAbsence(status, coverage);
    }
}
