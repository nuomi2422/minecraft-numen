package com.dwinovo.numen.rdd.side;

import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08: critical-only food supply gates. */
class FoodSupplySideTaskTypeTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    @Test void fullAndNoFoodDoesNotTrigger() {
        assertFalse(FoodSupplyPolicy.critical(18, 20, 0), "饱着没食物不该抢身体");
    }

    @Test void criticalHungerOrHpTriggers() {
        assertTrue(FoodSupplyPolicy.critical(5, 20, 0));
        assertTrue(FoodSupplyPolicy.critical(2, 20, 9));
        assertTrue(FoodSupplyPolicy.critical(20, 8, 9));
    }

    @Test void unknownHpOrHungerDoesNotTrigger() {
        assertFalse(FoodSupplyPolicy.critical(20, -1, 0));
        assertFalse(FoodSupplyPolicy.critical(-1, 20, 0));
    }

    @Test void cooldownBlocksRebuildWithinFiveMinutes() {
        assertTrue(FoodSupplyPolicy.cooldownActive(1000L + 60_000L, 1000L));
        assertFalse(FoodSupplyPolicy.cooldownActive(1000L + FoodSupplyPolicy.COOLDOWN_MS, 1000L));
    }

    @Test void completesAtHunger14OrTwoEdible() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = new SideTaskEngine(ev, token -> token);
        e.registerType(new FoodSupplySideTaskType());
        e.registerTrigger(new FoodSupplySideTaskTrigger());
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(4, 20, 0);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C));
        w.survival(14, 20, 0);
        e.tick(C, w, null, false);
        assertFalse(e.isActive(C));
        assertTrue(ev.saw("side_task_completed"));
    }
}
