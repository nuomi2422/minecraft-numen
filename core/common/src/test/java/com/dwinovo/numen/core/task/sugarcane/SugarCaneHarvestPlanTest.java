package com.dwinovo.numen.core.task.sugarcane;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07 修订：留根、只取最高 count 格、返回自上而下顺序。 */
class SugarCaneHarvestPlanTest {
    @Test void countOneTakesOnlyTop() {
        assertEquals(List.of(67), SugarCaneHarvestPlan.segmentsToBreak(List.of(64, 65, 66, 67), 1));
    }
    @Test void budgetTakesTopCellsDowntoBottom() {
        assertEquals(List.of(67, 66), SugarCaneHarvestPlan.segmentsToBreak(List.of(64, 65, 66, 67), 2));
        assertEquals(List.of(67, 66, 65), SugarCaneHarvestPlan.segmentsToBreak(List.of(64, 65, 66, 67), 3));
    }
    @Test void fullUpperWithBudgetEnough() {
        assertEquals(List.of(67, 66, 65), SugarCaneHarvestPlan.segmentsToBreak(List.of(64, 65, 66, 67), 10));
    }
    @Test void rootOnlyOrUnavailableIsNotHarvestable() {
        assertTrue(SugarCaneHarvestPlan.segmentsToBreak(List.of(64), 5).isEmpty());
        assertTrue(SugarCaneHarvestPlan.segmentsToBreak(List.of(), 5).isEmpty());
        assertFalse(SugarCaneHarvestPlan.hasHarvestableSegment(List.of(64)));
        assertTrue(SugarCaneHarvestPlan.hasHarvestableSegment(List.of(64, 65)));
    }
}
