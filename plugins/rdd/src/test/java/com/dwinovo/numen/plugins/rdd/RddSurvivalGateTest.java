package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RddSurvivalGateTest {

    @Test void hungryOrHurtOrFullBackpackRequiresEatingFirst() {
        assertTrue(RddSurvivalGate.mustEatFirst(6, 20, 3), "hunger 6 must plan eating first");
        assertTrue(RddSurvivalGate.mustEatFirst(19, 7.5, 3), "hp 7.5 must plan healing/eating first");
        assertTrue(RddSurvivalGate.mustEatFirst(19, 20, 0), "no free slot must free a slot before eating");
    }

    @Test void healthyCompanionDoesNotGetFoodSteps() {
        assertFalse(RddSurvivalGate.mustEatFirst(19, 20, 3));
        assertFalse(RddSurvivalGate.mustEatFirst(18, 8, 1));
    }
}
