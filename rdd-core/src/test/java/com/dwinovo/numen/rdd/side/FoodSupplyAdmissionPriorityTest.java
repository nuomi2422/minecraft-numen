package com.dwinovo.numen.rdd.side;

import com.dwinovo.numen.rdd.side.death.DeathRecoverySideTaskTrigger;
import com.dwinovo.numen.rdd.side.death.DeathRecoverySideTaskType;
import com.dwinovo.numen.rdd.side.recovery.RecoverySideTaskTrigger;
import com.dwinovo.numen.rdd.side.recovery.RecoverySideTaskType;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskTrigger;
import com.dwinovo.numen.rdd.side.sleep.SleepSideTaskType;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-13 V06 diagnostic: critical food admission competes with a due planner DAILY.
 * Run this class through the managed test-only entry. The original eight tests on
 * 5968 produced one DAILY-before-food failure and seven passing controls; added
 * predicate-count/pending-owner tests are not run yet.
 * A policy-only test misses Engine.tick's earlier tickRecurring return, so use the real
 * engine, food trigger, DAILY type/spec and token validation; only world/events are fakes.
 * The counterexample starts with no active side task/death/cooldown; controls preserve
 * existing death, recovery and sleep owners admitted through their production triggers.
 * No host/game, cooldown integration or whole-V06 cause is claimed by these tests.
 */
class FoodSupplyAdmissionPriorityTest {
    private static final UUID C = UUID.fromString("9be6bca3-fb62-4c56-9eb1-f55ab5293f0d");
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

    @Test
    void criticalEmptyInventoryWithoutDueDailyAdmitsFood() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = foodEngine(events);
        FakeWorldPort world = world(5, 6, 0);

        engine.tick(C, world, TOKEN, false);

        SideTask food = engine.current(C).orElseThrow();
        assertEquals(FoodSupplySideTaskType.TYPE_ID, food.typeId());
        assertSame(TOKEN, food.mainlineToken());
        assertEquals(1, events.count("side_task_created"));
        assertEquals(0, events.count("side_task_due"));
    }

    @Test
    void existingDeathRecoveryKeepsItsInstanceAgainstCriticalFoodAndDueDaily() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = foodEngine(events);
        engine.registerType(new DeathRecoverySideTaskType());
        engine.registerTrigger(new DeathRecoverySideTaskTrigger());
        FakeWorldPort world = world(18, 20, 0);

        DeathRecoverySideTaskTrigger.expect(C, 1, world.worldId(), "155,77,90",
                Map.of("minecraft:flint_and_steel", 1));
        try {
            engine.tick(C, world, TOKEN, true);
            SideTask death = engine.current(C).orElseThrow();
            assertEquals(DeathRecoverySideTaskType.TYPE_ID, death.typeId());
            assertEquals("1", death.data().get("death_seq"));
            assertEquals(0, DeathRecoverySideTaskTrigger.pendingCount(C));
            assertExistingOwnerRetained(engine, events, world, death, true);
        } finally {
            // Clean only this test's companion queue; never reset other companions.
            DeathRecoverySideTaskTrigger.take(C);
        }
    }

    @Test
    void existingRecoveryKeepsItsInstanceAgainstCriticalFoodAndDueDaily() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = foodEngine(events);
        engine.registerType(new RecoverySideTaskType());
        engine.registerTrigger(new RecoverySideTaskTrigger());
        FakeWorldPort world = world(18, 20, 0);

        RecoverySideTaskTrigger.expect(C, "minecraft:flint_and_steel", 1);
        try {
            engine.tick(C, world, TOKEN, false);
            SideTask recovery = engine.current(C).orElseThrow();
            assertEquals(RecoverySideTaskType.TYPE_ID, recovery.typeId());
            assertEquals("minecraft:flint_and_steel", recovery.data().get("item"));
            assertExistingOwnerRetained(engine, events, world, recovery, false);
        } finally {
            RecoverySideTaskTrigger.withdraw(C);
        }
    }

    @Test
    void existingSleepKeepsItsInstanceAgainstCriticalFoodAndDueDaily() {
        RecordingEvents events = new RecordingEvents();
        SideTaskEngine engine = foodEngine(events);
        engine.registerType(new SleepSideTaskType());
        engine.registerTrigger(new SleepSideTaskTrigger());
        FakeWorldPort world = world(18, 20, 0).day(false)
                .dayTime(31L * 24_000L + 13_000L);
        boolean wasEnabled = SleepSideTaskTrigger.enabled();

        SleepSideTaskTrigger.setEnabled(true);
        try {
            engine.tick(C, world, TOKEN, false);
            SideTask sleep = engine.current(C).orElseThrow();
            assertEquals(SleepSideTaskType.TYPE_ID, sleep.typeId());
            assertExistingOwnerRetained(engine, events, world, sleep, false);
        } finally {
            SleepSideTaskTrigger.setEnabled(wasEnabled);
        }
    }

    // 2026-11-13: count the production trigger's injected cooldown predicate, not a fake fires().
    // With non-null Engine contexts every fires() enters this predicate exactly once.
    @Test
    void dueDailyChecksAllowedCooldownExactlyOnceBeforeFoodAdmission() {
        RecordingEvents events = new RecordingEvents();
        AtomicInteger checks = new AtomicInteger();
        SideTaskEngine engine = countedFoodEngine(events, checks);
        registerDueDaily(engine);
        FakeWorldPort world = world(5, 6, 0);
        assertFalse(FoodSupplyPolicy.cooldownBlocks(true, 5, 6, 0),
                "a fresh critical empty-stock deficit bypasses the existing cooldown");

        engine.tick(C, world, TOKEN, false);

        assertEquals(1, checks.get(), "the registered cooldown predicate must run once, not be bypassed");
        assertEquals(FoodSupplySideTaskType.TYPE_ID, engine.current(C).orElseThrow().typeId());
        assertNotEquals(Boolean.TRUE, engine.handledToday(C, world.worldId(), DAILY_ID, 31L));
        assertEquals(1, events.count("side_task_created"));
    }

    @Test
    void dueDailyChecksDeniedCooldownExactlyOnceAndStillAdmitsDaily() {
        RecordingEvents events = new RecordingEvents();
        AtomicInteger checks = new AtomicInteger();
        SideTaskEngine engine = countedFoodEngine(events, checks);
        registerDueDaily(engine);
        FakeWorldPort world = world(12, 6, 1);
        assertTrue(FoodSupplyPolicy.needsSupply(12, 6, 1));
        assertTrue(FoodSupplyPolicy.cooldownBlocks(true, 12, 6, 1),
                "low hp with one edible item still respects the existing cooldown");

        engine.tick(C, world, TOKEN, false);

        assertEquals(1, checks.get(), "a false cooldown result must also be evaluated only once");
        assertEquals(DailyMaintenanceSideTaskType.TYPE_ID, engine.current(C).orElseThrow().typeId());
        assertEquals(Boolean.TRUE, engine.handledToday(C, world.worldId(), DAILY_ID, 31L));
        assertEquals(1, events.count("side_task_created"));
    }

    @Test
    void noDueDailyChecksAllowedCooldownExactlyOnce() {
        RecordingEvents events = new RecordingEvents();
        AtomicInteger checks = new AtomicInteger();
        SideTaskEngine engine = countedFoodEngine(events, checks);

        engine.tick(C, world(5, 6, 0), TOKEN, false);

        assertEquals(1, checks.get());
        assertEquals(FoodSupplySideTaskType.TYPE_ID, engine.current(C).orElseThrow().typeId());
        assertEquals(1, events.count("side_task_created"));
    }

    @Test
    void noDueDailyChecksDeniedCooldownExactlyOnceWithoutCreatingFood() {
        RecordingEvents events = new RecordingEvents();
        AtomicInteger checks = new AtomicInteger();
        SideTaskEngine engine = countedFoodEngine(events, checks);

        engine.tick(C, world(12, 6, 1), TOKEN, false);

        assertEquals(1, checks.get(), "a denied probe must not be retried by the fallback trigger loop");
        assertTrue(engine.current(C).isEmpty());
        assertEquals(0, events.count("side_task_created"));
    }

    @Test
    void existingRecoveryDoesNotEvaluateFoodCooldownAgain() {
        RecordingEvents events = new RecordingEvents();
        AtomicInteger checks = new AtomicInteger();
        SideTaskEngine engine = countedFoodEngine(events, checks);
        FakeWorldPort world = world(18, 20, 0);
        RecoverySideTaskTrigger.expect(C, "minecraft:flint_and_steel", 1);
        try {
            engine.tick(C, world, TOKEN, false);
            SideTask recovery = engine.current(C).orElseThrow();
            assertEquals(RecoverySideTaskType.TYPE_ID, recovery.typeId());
            assertEquals(0, checks.get(), "recovery precedes food in the host registration order");

            assertExistingOwnerRetained(engine, events, world, recovery, false);

            assertEquals(0, checks.get(), "an existing owner must return before automatic food admission");
        } finally {
            RecoverySideTaskTrigger.withdraw(C);
        }
    }

    @Test
    void pendingSleepKeepsTriggerOrderWhenCriticalFoodDefersDueDaily() {
        RecordingEvents events = new RecordingEvents();
        AtomicInteger checks = new AtomicInteger();
        SideTaskEngine engine = countedFoodEngine(events, checks);
        registerDueDaily(engine);
        FakeWorldPort world = world(5, 6, 0).day(false)
                .dayTime(31L * 24_000L + 13_000L);
        boolean wasEnabled = SleepSideTaskTrigger.enabled();
        SleepSideTaskTrigger.setEnabled(true);
        try {
            engine.tick(C, world, TOKEN, false);

            SideTask selected = engine.current(C).orElseThrow();
            assertEquals(SleepSideTaskType.TYPE_ID, selected.typeId(),
                    "daily yielding for food must not move food ahead of the pending sleep trigger");
            assertSame(TOKEN, selected.mainlineToken());
            assertEquals(1, checks.get());
            assertEquals(1, events.count("side_task_created"));
            assertNotEquals(Boolean.TRUE, engine.handledToday(C, world.worldId(), DAILY_ID, 31L));
        } finally {
            SleepSideTaskTrigger.setEnabled(wasEnabled);
        }
    }

    @Test
    void pendingRecoveryKeepsItsPayloadWhenCriticalFoodDefersDueDaily() {
        RecordingEvents events = new RecordingEvents();
        AtomicInteger checks = new AtomicInteger();
        SideTaskEngine engine = countedFoodEngine(events, checks);
        registerDueDaily(engine);
        FakeWorldPort world = world(5, 6, 0);
        RecoverySideTaskTrigger.expect(C, "minecraft:flint_and_steel", 1);
        try {
            engine.tick(C, world, TOKEN, false);

            SideTask selected = engine.current(C).orElseThrow();
            assertEquals(RecoverySideTaskType.TYPE_ID, selected.typeId(),
                    "daily yielding for food must not move food ahead of the pending recovery trigger");
            assertEquals("minecraft:flint_and_steel", selected.data().get("item"));
            assertEquals("1", selected.data().get("count"));
            assertTrue(RecoverySideTaskTrigger.take(C).isEmpty(), "create must consume exactly this payload");
            assertSame(TOKEN, selected.mainlineToken());
            assertEquals(1, checks.get());
            assertEquals(1, events.count("side_task_created"));
            assertNotEquals(Boolean.TRUE, engine.handledToday(C, world.worldId(), DAILY_ID, 31L));
        } finally {
            RecoverySideTaskTrigger.withdraw(C);
        }
    }

    private static SideTaskEngine countedFoodEngine(RecordingEvents events, AtomicInteger checks) {
        SideTaskEngine engine = new SideTaskEngine(events, token -> TOKEN.equals(token) ? token : null);
        engine.registerType(new DailyMaintenanceSideTaskType());
        engine.registerType(new SleepSideTaskType());
        engine.registerType(new RecoverySideTaskType());
        engine.registerType(new FoodSupplySideTaskType());
        engine.registerType(new DeathRecoverySideTaskType());
        // Match SideTaskHost's real order; do not insert a fake warmup/owner/priority trigger.
        engine.registerTrigger(new SleepSideTaskTrigger());
        engine.registerTrigger(new RecoverySideTaskTrigger());
        engine.registerTrigger(new FoodSupplySideTaskTrigger(ctx -> {
            checks.incrementAndGet();
            // Same policy as the host, fixed clock inputs so no sleep or wall-clock race is needed.
            boolean coolingDown = FoodSupplyPolicy.cooldownActive(1_000_001L, 1_000_000L);
            return !FoodSupplyPolicy.cooldownBlocks(coolingDown, ctx.hunger(), ctx.health(), ctx.edibleCount());
        }));
        engine.registerTrigger(new DeathRecoverySideTaskTrigger());
        return engine;
    }

    private static void assertExistingOwnerRetained(SideTaskEngine engine, RecordingEvents events,
                                                    FakeWorldPort world, SideTask existing,
                                                    boolean deathOccupying) {
        registerDueDaily(engine);
        world.survival(5, 6, 0);
        world.gameTime++;
        world.dayTime++;
        assertTrue(FoodSupplyPolicy.needsSupply(5, 6, 0));

        engine.tick(C, world, TOKEN, deathOccupying);

        SideTask retained = engine.current(C).orElseThrow();
        assertEquals(existing.typeId(), retained.typeId());
        assertEquals(existing.instanceId(), retained.instanceId());
        assertEquals(existing.generation(), retained.generation());
        assertEquals(existing.deadlineGameTime(), retained.deadlineGameTime());
        assertSame(TOKEN, retained.mainlineToken());
        assertEquals(1, events.count("side_task_created"));
        assertEquals(0, events.count("side_task_cancelled"));
        assertEquals(0, events.count("side_task_completed"));
        assertNotEquals(Boolean.TRUE, engine.handledToday(C, world.worldId(), DAILY_ID, 31L));
    }

    private static FakeWorldPort world(int hunger, int health, int edible) {
        return new FakeWorldPort().at(31L * 24_000L + 100L, 31L).day(true)
                .survival(hunger, health, edible);
    }

    private static SideTaskEngine engineWithDueDaily(RecordingEvents events) {
        SideTaskEngine engine = foodEngine(events);
        registerDueDaily(engine);
        return engine;
    }

    private static SideTaskEngine foodEngine(RecordingEvents events) {
        SideTaskEngine engine = new SideTaskEngine(events, token -> TOKEN.equals(token) ? token : null);
        engine.registerType(new DailyMaintenanceSideTaskType());
        engine.registerType(new FoodSupplySideTaskType());
        // The host uses this production trigger with its cooldown predicate; no cooldown here.
        engine.registerTrigger(new FoodSupplySideTaskTrigger());
        return engine;
    }

    private static void registerDueDaily(SideTaskEngine engine) {
        engine.registerRecurring(new RecurringSideTaskSpec(
                DAILY_ID, DailyMaintenanceSideTaskType.TYPE_ID, 1, 31L,
                RecurringSideTaskSpec.MissedPolicy.SKIP, true, false,
                Map.of("description", "inspect existing trading station",
                        "asset_key", "minecraft:lectern", "minimum", "1")),
                DailyMaintenanceSideTaskType.TYPE_ID);
    }
}
