package com.dwinovo.numen.rdd.side.death;

import com.dwinovo.numen.rdd.side.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-12: strategy acceptance must not confuse absent drops with preparation success.
 * Uses the production engine + strategy data, not a second recovery state machine.
 * This test-only revision intentionally fails on e826; no Minecraft or build is needed to read it.
 */
class DeathPreparationRegressionTest {
    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000dc");
    private static final class Port implements SideTaskWorldPort {
        long now = 1000;
        DeathDropState drops = DeathDropState.DROPS_ABSENT;
        public long gameTime() { return now; }
        public long dayTime() { return 1000; }
        public long gameDay() { return 0; }
        public boolean hasDayNightCycle() { return true; }
        public String worldId() { return "minecraft:overworld"; }
        public boolean isSleeping(UUID id) { return false; }
        public boolean isDay() { return true; }
        public Wake pollWake(UUID id) { return Wake.NONE; }
        public DeathRecoveryFacts deathRecoveryFacts(UUID id, SideTask task) {
            return new DeathRecoveryFacts(drops, false, false, true, "loaded area; armor not verified");
        }
    }
    private static SideTaskEngine engine(Port port) {
        var engine = new SideTaskEngine(null, token -> token);
        engine.registerType(new DeathRecoverySideTaskType());
        assertTrue(engine.startNow(new DeathRecoverySideTaskType(), C, port, null,
                Map.of("death_seq", "7")));
        return engine;
    }
    @Test void selectedPrepareSurvivesAbsentDropsUntilEquipmentIsVerified() {
        var port = new Port();
        var engine = engine(port);
        assertTrue(engine.setData(C, "decision", "PREPARE_AND_REPLAN"));
        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C), "DROPS_ABSENT only closes the pickup route; preparation is not done");
        assertEquals(DeathRecoverySideTaskType.PHASE_REEQUIP, engine.current(C).orElseThrow().phase());
    }
    @Test void recoveredDropsDoNotCompleteSelectedPreparationWithoutWearingEvidence() {
        var port = new Port();
        port.drops = SideTaskWorldPort.DeathDropState.RECOVERED;
        var engine = engine(port);
        assertTrue(engine.setData(C, "decision", "FETCH_BACKUP_THEN_CONTINUE"));
        engine.tick(C, port, null, false);
        assertTrue(engine.isActive(C), "backpack recovery is not current equipped-slot evidence");
    }
    @Test void unprovenPreparationStillHasABoundedExit() {
        var port = new Port();
        var engine = engine(port);
        engine.setData(C, "decision", "PREPARE_AND_REPLAN");
        port.now += DeathRecoverySideTaskType.DEADLINE_TICKS + 1;
        engine.tick(C, port, null, false);
        assertFalse(engine.isActive(C), "unknown equipment must not freeze the branch forever");
    }
}
