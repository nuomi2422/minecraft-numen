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


    @Test void completionWhileStillCriticalWaitsForHostCooldown() {
        CooldownHost host = new CooldownHost();
        // 2026-11-14: settle with real stock, then restore the deficit to test the same cooldown.
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(14, 8, 0);
        host.tick(C, w);
        w.survival(14, 8, 2);
        host.tick(C, w);
        w.survival(14, 8, 0);
        assertEquals(1, host.events.count("side_task_completed"));
        assertFalse(host.engine.isActive(C));
        host.nowMs += FoodSupplyPolicy.COOLDOWN_MS - 1;
        host.tick(C, w);
        assertFalse(host.engine.isActive(C), "automatic trigger must honor the host cooldown");
        assertEquals(1, host.events.count("side_task_created"));
        host.nowMs++;
        host.tick(C, w);
        assertTrue(host.engine.isActive(C), "persistent need may retry at the cooldown boundary");
        assertEquals(2, host.events.count("side_task_created"));
    }

    @Test void timedOutLowHpTaskCannotImmediatelyRecreateWithoutFoodDeficit() {
        CooldownHost host = new CooldownHost();
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(7, 8, 0);
        host.tick(C, w);
        long deadline = host.engine.current(C).orElseThrow().deadlineGameTime();
        w.at(deadline + 1, 0);
        host.tick(C, w);
        assertTrue(host.events.saw("side_task_skipped"));
        host.tick(C, w);
        assertFalse(host.engine.isActive(C));
        assertEquals(1, host.events.count("side_task_created"));
        host.nowMs += FoodSupplyPolicy.COOLDOWN_MS;
        host.tick(C, w);
        assertTrue(host.engine.isActive(C));
    }

    @Test void cooldownBelongsToOnlyTheSettledCompanion() {
        CooldownHost host = new CooldownHost();
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(14, 8, 0);
        host.tick(C, w);
        // 2026-11-14: valid stock settlement preserves the companion-isolation assertion below.
        w.survival(14, 8, 2);
        host.tick(C, w);
        w.survival(14, 8, 0);
        host.tick(C, w);
        assertFalse(host.engine.isActive(C));
        UUID other = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        host.tick(other, w);
        assertTrue(host.engine.isActive(other), "another companion has no food cooldown");
    }

    @Test void admissionCooldownDoesNotBlockActiveTaskCompletion() {
        CooldownHost host = new CooldownHost();
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(3, 20, 0);
        host.tick(C, w);
        // SURVIVAL's startNow path claims the host slot before its active task is evaluated.
        host.lastDoneMs.put(C, host.nowMs);
        w.survival(14, 20, 0);
        host.tick(C, w);
        assertFalse(host.engine.isActive(C));
        assertTrue(host.events.saw("side_task_completed"));
    }

    @Test void realFoodDeficitDuringCooldownMustStillStartSupply() {
        CooldownHost host = new CooldownHost();
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(14, 8, 0);
        host.tick(C, w);
        // 2026-11-14: settle on two items; the later empty critical-hunger deficit is unchanged.
        w.survival(14, 8, 2);
        host.tick(C, w);
        w.survival(14, 8, 0);
        assertFalse(host.engine.isActive(C));
        host.nowMs += 1_000L;
        w.survival(3, 8, 0);
        host.tick(C, w);
        assertTrue(host.engine.isActive(C), "new critical hunger with no food must bypass cooldown");
    }

    @Test void lowHungerWithEnoughFoodMustNotCreateCompletedTaskLoop() {
        CooldownHost host = new CooldownHost();
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(3, 8, 2);
        host.tick(C, w);
        assertFalse(host.engine.isActive(C), "existing food already meets supply completion; eating owns it");
        host.tick(C, w);
        host.nowMs += FoodSupplyPolicy.COOLDOWN_MS;
        host.tick(C, w);
        assertEquals(0, host.events.count("side_task_created"));
    }

    @Test void cooldownExceptionUsesExistingHungerThresholdsAndConfirmedEmptyInventory() {
        assertFalse(FoodSupplyPolicy.cooldownBlocks(true, 6, 20, 0));
        assertTrue(FoodSupplyPolicy.cooldownBlocks(true, 7, 8, 0));
        assertTrue(FoodSupplyPolicy.cooldownBlocks(true, 3, 8, 1));
        assertTrue(FoodSupplyPolicy.cooldownBlocks(true, 3, 8, -1));
        assertTrue(FoodSupplyPolicy.cooldownBlocks(true, -1, 8, 0));
        assertTrue(FoodSupplyPolicy.cooldownBlocks(true, 3, 0, 0));
    }

    @Test void timedOutTaskCanRestartForPersistentCriticalFoodDeficit() {
        CooldownHost host = new CooldownHost();
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(3, 20, 0);
        host.tick(C, w);
        w.at(host.engine.current(C).orElseThrow().deadlineGameTime(), 0);
        host.tick(C, w);
        assertTrue(host.events.saw("side_task_skipped"));
        host.tick(C, w);
        assertTrue(host.engine.isActive(C), "time-based retry suppression cannot suppress real food deficit");
    }

    @Test void explicitSurvivalRequestUsesTheSameCooldownException() {
        long settledAt = 1000L;
        boolean coolingDown = FoodSupplyPolicy.cooldownActive(settledAt + 1000L, settledAt);
        assertTrue(FoodSupplyPolicy.cooldownBlocks(coolingDown, 14, 8, 0));
        assertFalse(FoodSupplyPolicy.cooldownBlocks(coolingDown, 3, 8, 0),
                "the host requestFoodSupply gate must admit the new deficit");
        assertTrue(FoodSupplyPolicy.cooldownBlocks(coolingDown, 3, 8, 2));
    }

    /**
     * 2026-11-14: V13 created low-hp supply at hunger 15, then completed without two food items.
     * Drive the existing engine, type and trigger with FakeWorldPort; supply is not healing/eating.
     */
    @Test void lowHpAndHunger15WithoutFoodMustNotCompleteSupply() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = new SideTaskEngine(ev, token -> token);
        e.registerType(new FoodSupplySideTaskType());
        e.registerTrigger(new FoodSupplySideTaskTrigger());
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(15, 4, 0);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C), "the real critical-health trigger must start supply");
        e.tick(C, w.at(120, 0), null, false);
        assertTrue(e.isActive(C), "high hunger is not recovery or a two-item supply while hp stays low");
        assertFalse(ev.saw("side_task_completed"), "no replenishment occurred");
    }

    @Test void lowHpAndHunger15WithOneEdibleMustNotCompleteSupply() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = new SideTaskEngine(ev, token -> token);
        e.registerType(new FoodSupplySideTaskType());
        e.registerTrigger(new FoodSupplySideTaskTrigger());
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(15, 4, 1);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C), "one edible item remains below the existing supply stock");
        e.tick(C, w.at(120, 0), null, false);
        assertTrue(e.isActive(C), "one item plus high hunger cannot settle the low-hp supply task");
        assertFalse(ev.saw("side_task_completed"));
    }

    @Test void unknownHealthAfterLowHpStartMustNotCountAsHealthyCompletion() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = new SideTaskEngine(ev, token -> token);
        e.registerType(new FoodSupplySideTaskType());
        e.registerTrigger(new FoodSupplySideTaskTrigger());
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(15, 4, 0);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C));
        w.at(120, 0).survival(15, -1, 0);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C), "UNKNOWN health does not prove that the low-hp need ended");
        assertFalse(ev.saw("side_task_completed"));
    }

    @Test void twoEdibleItemsCompleteSupplyWhileHealthRemainsLow() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = new SideTaskEngine(ev, token -> token);
        e.registerType(new FoodSupplySideTaskType());
        e.registerTrigger(new FoodSupplySideTaskTrigger());
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(15, 4, 0);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C));
        w.at(120, 0).survival(15, 4, 2);
        e.tick(C, w, null, false);
        assertFalse(e.isActive(C), "two confirmed edible items finish supply; eating/healing is separate");
        assertTrue(ev.saw("side_task_completed"));
    }

    @Test void healthyHunger14WithoutStockStillCompletesActiveSupply() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = new SideTaskEngine(ev, token -> token);
        e.registerType(new FoodSupplySideTaskType());
        e.registerTrigger(new FoodSupplySideTaskTrigger());
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(4, 20, 0);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C));
        w.at(120, 0).survival(14, 20, 0);
        e.tick(C, w, null, false);
        assertFalse(e.isActive(C), "preserve the existing healthy hunger-14 completion boundary");
        assertTrue(ev.saw("side_task_completed"));
    }

    /** 2026-11-14: preserve the existing hp-8, hunger-14 and two-item supply boundaries. */
    @Test void hp8StillRequiresTwoFoodItemsAtHunger14() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = new SideTaskEngine(ev, token -> token);
        e.registerType(new FoodSupplySideTaskType());
        e.registerTrigger(new FoodSupplySideTaskTrigger());
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(14, 8, 1);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C));
        e.tick(C, w.at(120, 0), null, false);
        assertTrue(e.isActive(C), "hp 8 remains critical; hunger 14 plus one item cannot settle supply");
        assertFalse(ev.saw("side_task_completed"));
    }

    @Test void hp9AndHunger14CompleteWithoutStock() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = new SideTaskEngine(ev, token -> token);
        e.registerType(new FoodSupplySideTaskType());
        e.registerTrigger(new FoodSupplySideTaskTrigger());
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(3, 9, 0);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C));
        w.at(120, 0).survival(14, 9, 0);
        e.tick(C, w, null, false);
        assertFalse(e.isActive(C), "hp above 8 and hunger at 14 satisfy the healthy completion path");
        assertTrue(ev.saw("side_task_completed"));
    }

    @Test void healthyHpWithHunger13StillNeedsSupplyStock() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = new SideTaskEngine(ev, token -> token);
        e.registerType(new FoodSupplySideTaskType());
        e.registerTrigger(new FoodSupplySideTaskTrigger());
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(3, 9, 0);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C));
        w.at(120, 0).survival(13, 9, 0);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C), "healthy hp alone cannot lower the existing hunger-14 boundary");
        assertFalse(ev.saw("side_task_completed"));
    }

    @Test void unknownVitalsNeedConfirmedStockForCompletion() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = new SideTaskEngine(ev, token -> token);
        e.registerType(new FoodSupplySideTaskType());
        e.registerTrigger(new FoodSupplySideTaskTrigger());
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(3, 9, 0);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C));
        w.at(120, 0).survival(-1, -1, -1);
        e.tick(C, w, null, false);
        assertTrue(e.isActive(C), "UNKNOWN hunger, hp and stock cannot prove completion");
        assertFalse(ev.saw("side_task_completed"));
        w.at(140, 0).survival(-1, -1, 2);
        e.tick(C, w, null, false);
        assertFalse(e.isActive(C), "two confirmed items complete supply even while vitals remain UNKNOWN");
        assertTrue(ev.saw("side_task_completed"));
    }

    /** Uses the real engine/trigger with the host's existing settlement bookkeeping. */
    private static final class CooldownHost {
        long nowMs = 1_000L;
        final Map<UUID, Long> lastDoneMs = new java.util.HashMap<>();
        final RecordingEvents events = new RecordingEvents();
        final SideTaskEngine engine = new SideTaskEngine(events, token -> token);

        CooldownHost() {
            engine.registerType(new FoodSupplySideTaskType());
            engine.registerTrigger(new FoodSupplySideTaskTrigger(ctx ->
                    !FoodSupplyPolicy.cooldownBlocks(
                            FoodSupplyPolicy.cooldownActive(nowMs, lastDoneMs.getOrDefault(ctx.companionId(), 0L)),
                            ctx.hunger(), ctx.health(), ctx.edibleCount())));
        }

        void tick(UUID id, FakeWorldPort world) {
            boolean wasFood = engine.current(id)
                    .map(t -> FoodSupplySideTaskType.TYPE_ID.equals(t.typeId())).orElse(false);
            engine.tick(id, world, null, false);
            if (wasFood && !engine.isActive(id)) lastDoneMs.put(id, nowMs);
        }
    }

    @Test void forcedStartIgnoresThresholdAndIsIdempotent() {
        RecordingEvents ev = new RecordingEvents();
        SideTaskEngine e = new SideTaskEngine(ev, token -> token);
        e.registerType(new FoodSupplySideTaskType());
        FakeWorldPort w = new FakeWorldPort().at(100, 0).survival(20, 20, 9);
        assertTrue(e.startNow(new FoodSupplySideTaskType(), C, w, null, Map.of("reason", "test")));
        assertTrue(e.isActive(C));
        assertFalse(e.startNow(new FoodSupplySideTaskType(), C, w, null, Map.of()), "重复上报不得重建");
        assertEquals("test", e.current(C).orElseThrow().data().get("reason"));
    }
}
