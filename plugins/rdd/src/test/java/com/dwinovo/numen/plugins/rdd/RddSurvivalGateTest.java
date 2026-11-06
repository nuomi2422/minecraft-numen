package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RddSurvivalGateTest {

    @Test void hungryOrHurtRequiresEatingFirst() {
        assertEquals(RddSurvivalGate.Decision.EAT_FIRST, RddSurvivalGate.decide(6, 20, 3, false));
        assertEquals(RddSurvivalGate.Decision.EAT_FIRST, RddSurvivalGate.decide(19, 7.5, 3, false));
    }

    @Test void hungryWithFullBackpackStillEatsFirstThenFreesSlot() {
        assertEquals(RddSurvivalGate.Decision.EAT_FIRST, RddSurvivalGate.decide(6, 20, 0, true));
    }

    @Test void fullBackpackAloneIsNotHunger() {
        assertEquals(RddSurvivalGate.Decision.NONE, RddSurvivalGate.decide(19, 20, 0, false));
        assertEquals(RddSurvivalGate.Decision.MAKE_ROOM_FIRST, RddSurvivalGate.decide(19, 20, 0, true));
    }

    @Test void healthyCompanionDoesNotGetFoodSteps() {
        assertEquals(RddSurvivalGate.Decision.NONE, RddSurvivalGate.decide(19, 20, 3, false));
    }
}
