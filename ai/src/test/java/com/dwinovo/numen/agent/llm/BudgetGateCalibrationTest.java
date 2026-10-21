package com.dwinovo.numen.agent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * G4c-s109c A 复审 MUST-FIX 1/3：校准对要跨压缩保留、换 provider 要失效。
 */
class BudgetGateCalibrationTest {

    @Test
    void coldStartUsesCurrentTrimmed() {
        BudgetGateCalibration c = new BudgetGateCalibration();
        assertEquals(1234L, c.gate(1234L, "p1|m1"));
        assertEquals("trimmed", c.source("p1|m1"));
        assertFalse(c.usable("p1|m1"));
    }

    @Test
    void calibratesWithinSameProvider() {
        BudgetGateCalibration c = new BudgetGateCalibration();
        c.update(800L, 1000L, "p1|m1");
        assertTrue(c.usable("p1|m1"));
        assertEquals(1600L, c.gate(2000L, "p1|m1"));
        assertEquals("calibrated", c.source("p1|m1"));
        assertEquals(0.8, c.ratio("p1|m1"), 1e-9);
    }

    @Test
    void providerChangeInvalidatesCalibration() {
        BudgetGateCalibration c = new BudgetGateCalibration();
        c.update(800L, 1000L, "p1|m1");
        assertFalse(c.usable("p2|m2"), "换 provider/model 后旧比例不可用");
        assertEquals(2000L, c.gate(2000L, "p2|m2"), "换 provider 后本轮原样用 trimmed");
        assertEquals("trimmed", c.source("p2|m2"));
    }

    @Test
    void survivesContextClearButClearResetsCalibration() {
        BudgetGateCalibration c = new BudgetGateCalibration();
        c.update(800L, 1000L, "p1|m1");
        // 压缩/清空上下文**不**调用 clear()；模拟历史清空后仍可用
        assertTrue(c.usable("p1|m1"));
        assertEquals(1600L, c.gate(2000L, "p1|m1"));
        c.clear();
        assertFalse(c.usable("p1|m1"));
        assertEquals(2000L, c.gate(2000L, "p1|m1"));
    }

    @Test
    void invalidSamplesDoNotOverwriteValidCalibration() {
        BudgetGateCalibration c = new BudgetGateCalibration();
        c.update(800L, 1000L, "p1|m1");
        c.update(0L, 999L, "p1|m1");
        c.update(800L, -1L, "p1|m1");
        assertEquals(800L, c.measured());
        assertEquals(1000L, c.trimmed());
        assertEquals(1600L, c.gate(2000L, "p1|m1"));
    }
}
