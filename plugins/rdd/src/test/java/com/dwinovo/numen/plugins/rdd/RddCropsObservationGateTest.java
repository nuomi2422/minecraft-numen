package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.ObservationMetadata;
import com.dwinovo.numen.api.observation.WorldObservationService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07：PARTIAL/过期/截断不可当“没有”；FULL+CURRENT 才正常。 */
class RddCropsObservationGateTest {
    @Test void incompleteNoteSaysNotEqualEmpty() {
        String note = RddCropsObservationGate.incompleteNote();
        assertTrue(note.contains("未确认") && note.contains("不等于没有") && note.contains("覆盖不足"), note);
    }
    @Test void fullCurrentNotTruncatedIsComplete() {
        assertTrue(RddCropsObservationGate.complete(WorldObservationService.Status.OK,
                WorldObservationService.Coverage.FULL, ObservationMetadata.Freshness.CURRENT, 3, 64));
    }
    /** 2026-11-07 修正：说“有”不需要扫全——采样查询天生 PARTIAL，不能因此丢掉已看到的成熟作物。 */
    @Test void presenceUsableAcceptsPartialButNotStaleOrError() {
        assertTrue(RddCropsObservationGate.presenceUsable(WorldObservationService.Status.OK,
                ObservationMetadata.Freshness.CURRENT), "OK+CURRENT 即可说“有”");
        assertFalse(RddCropsObservationGate.presenceUsable(WorldObservationService.Status.OK,
                ObservationMetadata.Freshness.STALE), "过期样本不能当现在");
        assertFalse(RddCropsObservationGate.presenceUsable(WorldObservationService.Status.ERROR,
                ObservationMetadata.Freshness.CURRENT), "ERROR 不可用");
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
