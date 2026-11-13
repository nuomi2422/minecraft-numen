package com.dwinovo.numen.rdd.side;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-13 V06 diagnostic: critical food admission competes with a due planner DAILY.
 * Run this class through the managed test-only entry; it has not been run on ec1b.
 * A policy-only test misses Engine.tick's earlier tickRecurring return, so use the real
 * engine, food trigger, DAILY type/spec and token validation; only world/events are fakes.
 * Scope: no active side task, no death occupancy, no host cooldown. This does not prove
 * V06's whole failure cause or change the separate stock/eating completion contracts.
 */
class FoodSupplyAdmissionPriorityTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String DAILY_ID = "daily:goal-food-test:maintenance";
    private static final MainlineResumeToken TOKEN = new MainlineResumeToken(
            C, "chain-food-test", "goal-food-test", "primary", "food-step", 31, 1L);

    @Test
    void criticalEmptyInventoryAdmitsFoodBeforeDuePlannerDaily() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engineWithDueDaily(events);
        FakeWorldPort world = world(5, 6, 0);

        assertTrue(FoodSupplyPolicy.needsSupply(5, 6, 0));
        engine.tick(C, world, TOKEN, false);

        // Expected RED on ec1b: tickRecurring currently returns with DAILY before food fires.
        SideTask selected = engine.current(C).orElseThrow();
        assertEquals(FoodSupplySideTaskType.TYPE_ID, selected.typeId(),
                "confirmed critical food deficit must be admitted before advisory maintenance");
        assertSame(TOKEN, selected.mainlineToken());
        assertEquals(1, events.count("side_task_created"));
        assertNotEquals(Boolean.TRUE, engine.handledToday(C, world.worldId(), DAILY_ID, 31L),
                "deferring maintenance for food must not spend its due occurrence");
    }

    @Test
    void safeBodyStillAdmitsTheSameDuePlannerDaily() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engineWithDueDaily(events);
        FakeWorldPort world = world(18, 20, 0);

        assertFalse(FoodSupplyPolicy.needsSupply(18, 20, 0));
        engine.tick(C, world, TOKEN, false);

        assertEquals(DailyMaintenanceSideTaskType.TYPE_ID, engine.current(C).orElseThrow().typeId());
        assertEquals(Boolean.TRUE, engine.handledToday(C, world.worldId(), DAILY_ID, 31L));
        assertEquals(1, events.count("side_task_created"));
    }

    @Test
    void busyBodyBypassesRecurringAndReachesTheSameFoodTrigger() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engineWithDueDaily(events);
        FakeWorldPort world = world(5, 6, 0).bodyBusy(true);

        engine.tick(C, world, TOKEN, false);

        assertEquals(FoodSupplySideTaskType.TYPE_ID, engine.current(C).orElseThrow().typeId());
        assertSame(TOKEN, engine.current(C).orElseThrow().mainlineToken());
        assertNotEquals(Boolean.TRUE, engine.handledToday(C, world.worldId(), DAILY_ID, 31L));
        assertEquals(1, events.count("side_task_created"));
    }

    @Test
    void twoEdibleItemsKeepSupplySeparateFromEating() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = engineWithDueDaily(events);
        FakeWorldPort world = world(3, 6, 2);

        assertFalse(FoodSupplyPolicy.needsSupply(3, 6, 2),
                "existing stock already meets supply completion; hunger recovery remains separate");
        engine.tick(C, world, TOKEN, false);

        assertEquals(DailyMaintenanceSideTaskType.TYPE_ID, engine.current(C).orElseThrow().typeId());
        assertEquals(1, events.count("side_task_created"));
    }

    private static FakeWorldPort world(int hunger, int health, int edible) {
        return new FakeWorldPort().at(31L * 24_000L + 100L, 31L).day(true)
                .survival(hunger, health, edible);
    }

    private static SideTaskEngine engineWithDueDaily(RecordingEvents events) {
        SideTaskEngine engine = new SideTaskEngine(events, token -> TOKEN.equals(token) ? token : null);
        engine.registerType(new DailyMaintenanceSideTaskType());
        engine.registerType(new FoodSupplySideTaskType());
        // The host uses this production trigger with its cooldown predicate; no cooldown here.
        engine.registerTrigger(new FoodSupplySideTaskTrigger());
        engine.registerRecurring(new RecurringSideTaskSpec(
                DAILY_ID, DailyMaintenanceSideTaskType.TYPE_ID, 1, 31L,
                RecurringSideTaskSpec.MissedPolicy.SKIP, true, false,
                Map.of("description", "inspect existing trading station",
                        "asset_key", "minecraft:lectern", "minimum", "1")),
                DailyMaintenanceSideTaskType.TYPE_ID);
        return engine;
    }
}
