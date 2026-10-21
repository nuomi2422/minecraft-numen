package com.dwinovo.numen.agent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * G4c-s109b A-M1：判额校准的纯函数判据。
 *
 * <p>这层最要紧的是**失败方向**：没有历史时必须原样用本轮估算，不能拿 0 或上一轮残留；
 * 比例异常时夹取，不能让一次样本把闸门带飞。
 */
class BudgetGateEstimateTest {

    @Test
    void coldStartUsesCurrentTrimmedEstimate() {
        assertEquals(1234L, BudgetGateEstimate.calibrated(1234L, 0L, 0L));
        assertEquals("trimmed", BudgetGateEstimate.source(0L, 0L));
    }

    @Test
    void calibratesCurrentEstimateByLastPair() {
        // 上一轮：本地估 1000，厂商实测 800 → 0.8；本轮本地估 2000 → 1600
        assertEquals(1600L, BudgetGateEstimate.calibrated(2000L, 800L, 1000L));
        assertEquals("calibrated", BudgetGateEstimate.source(800L, 1000L));
    }

    @Test
    void clampsAbsurdRatios() {
        // 实测 10000 / 估 1 = 10000× → 夹到 2.0
        assertEquals(200L, BudgetGateEstimate.calibrated(100L, 10_000L, 1L));
        // 实测 1 / 估 10000 ≈ 0 → 夹到 0.2
        assertEquals(2000L, BudgetGateEstimate.calibrated(10_000L, 1L, 10_000L));
    }

    @Test
    void zeroOrNegativeInputsNeverProduceNegativeGate() {
        assertEquals(0L, BudgetGateEstimate.calibrated(-5L, 800L, 1000L));
        assertEquals(500L, BudgetGateEstimate.calibrated(500L, -1L, 1000L));
        assertEquals(500L, BudgetGateEstimate.calibrated(500L, 800L, 0L));
    }

    @Test
    void ratioOneIsEquivalentToTrimmed() {
        assertEquals(777L, BudgetGateEstimate.calibrated(777L, 1000L, 1000L));
        assertEquals(1.0, BudgetGateEstimate.rawRatio(1000L, 1000L), 1e-9);
        assertEquals(0.0, BudgetGateEstimate.rawRatio(0L, 1000L), 1e-9);
    }
}
