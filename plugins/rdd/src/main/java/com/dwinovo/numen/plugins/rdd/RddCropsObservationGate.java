package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.ObservationMetadata;
import com.dwinovo.numen.api.observation.WorldObservationService;

/** 2026-11-07：作物扫描能否当“完整观测”用（纯判据，可测）。 */
final class RddCropsObservationGate {
    private RddCropsObservationGate() {}

    /** 只有 OK+FULL+CURRENT 且未截断才可信；PARTIAL/截断/过期都必须先 refresh 或判不可用。 */
    static boolean complete(WorldObservationService.Status status,
                            WorldObservationService.Coverage coverage,
                            ObservationMetadata.Freshness freshness,
                            int itemCount, int maxItems) {
        return status == WorldObservationService.Status.OK
                && coverage == WorldObservationService.Coverage.FULL
                && freshness == ObservationMetadata.Freshness.CURRENT
                && itemCount < maxItems;
    }
}
