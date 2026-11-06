package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RddSurvivalGateTest {

    @Test void hungryOrHurtRequiresEatingFirst() {
        assertEquals(RddSurvivalGate.Decision.EAT_FIRST, RddSurvivalGate.evaluate(6, 20, 3, false));
        assertEquals(RddSurvivalGate.Decision.EAT_FIRST, RddSurvivalGate.evaluate(19, 7.5, 3, false));
    }

    @Test void hungryWithFullBackpackStillEatsFirstThenFreesSlot() {
        assertEquals(RddSurvivalGate.Decision.EAT_FIRST, RddSurvivalGate.evaluate(6, 20, 0, true));
    }

    @Test void fullBackpackAloneIsNotHunger() {
        assertEquals(RddSurvivalGate.Decision.NONE, RddSurvivalGate.evaluate(19, 20, 0, false));
        assertEquals(RddSurvivalGate.Decision.MAKE_ROOM_FIRST, RddSurvivalGate.evaluate(19, 20, 0, true));
    }

    @Test void healthyCompanionDoesNotGetFoodSteps() {
        assertEquals(RddSurvivalGate.Decision.NONE, RddSurvivalGate.evaluate(19, 20, 3, false));
    }

    // 2026-11-06 21:44 实机：hunger 6、包里只有小麦和种子 ⇒ 每分钟插“去吃”却永远吃不上。
    @Test void hungryWithoutEdibleFoodMustSeekFoodInsteadOfEating() {
        assertEquals(RddSurvivalGate.Decision.SEEK_FOOD_FIRST,
                RddSurvivalGate.evaluate(6, 20, 11, false, false));
        assertEquals(RddSurvivalGate.Decision.EAT_FIRST,
                RddSurvivalGate.evaluate(6, 20, 11, false, true));
    }

    @Test void edibleFoodIsDetectedByWhitelistNotByName() {
        assertEquals(false, RddSurvivalGate.hasEdibleFood(java.util.Map.of(
                "minecraft:wheat", 1, "minecraft:wheat_seeds", 13)));
        assertEquals(true, RddSurvivalGate.hasEdibleFood(java.util.Map.of("minecraft:bread", 1)));
        assertEquals(true, RddSurvivalGate.hasEdibleFood(java.util.Map.of("minecraft:beef", 2)));
        assertEquals(false, RddSurvivalGate.hasEdibleFood(java.util.Map.of()));
    }

    @Test void noFoodButHealthyIsNotASurvivalTask() {
        assertEquals(RddSurvivalGate.Decision.NONE, RddSurvivalGate.evaluate(19, 20, 11, false, false));
    }
}
