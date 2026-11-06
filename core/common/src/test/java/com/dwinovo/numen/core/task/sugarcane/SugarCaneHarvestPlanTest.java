package com.dwinovo.numen.core.task.sugarcane;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07：留根（保底根只破上段）、预算截断、只有底根不叫可收。 */
class SugarCaneHarvestPlanTest {
    @Test void keepsBottomRoot() {
        assertEquals(List.of(65, 66, 67), SugarCaneHarvestPlan.segmentsToBreak(List.of(64, 65, 66, 67), 10));
    }
    @Test void budgetCutsFromBottom() {
        assertEquals(List.of(65, 66), SugarCaneHarvestPlan.segmentsToBreak(List.of(64, 65, 66, 67), 2));
    }
    @Test void rootOnlyOrUnavailableIsNotHarvestable() {
        assertTrue(SugarCaneHarvestPlan.segmentsToBreak(List.of(64), 5).isEmpty());
        assertTrue(SugarCaneHarvestPlan.segmentsToBreak(List.of(), 5).isEmpty());
        assertFalse(SugarCaneHarvestPlan.hasHarvestableSegment(List.of(64)));
        assertTrue(SugarCaneHarvestPlan.hasHarvestableSegment(List.of(64, 65)));
    }
}
