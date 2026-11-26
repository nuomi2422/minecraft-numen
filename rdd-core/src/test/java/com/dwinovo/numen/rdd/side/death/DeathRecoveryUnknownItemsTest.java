package com.dwinovo.numen.rdd.side.death;

import com.dwinovo.numen.rdd.side.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A2: an empty instantaneous death item table is UNKNOWN, never proof of no loss or permanent loss. */
class DeathRecoveryUnknownItemsTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    private static final class Port implements SideTaskWorldPort {
        long now = 1000;
        SideTaskWorldPort.DeathDropState drops = SideTaskWorldPort.DeathDropState.DROPS_ABSENT;
        public long gameTime() { return now; }
        public long dayTime() { return 1000; }
        public long gameDay() { return 0; }
        public boolean hasDayNightCycle() { return true; }
        public String worldId() { return "minecraft:overworld"; }
        public boolean isSleeping(UUID id) { return false; }
        public boolean isDay() { return true; }
        public Wake pollWake(UUID id) { return Wake.NONE; }
        public DeathRecoveryFacts deathRecoveryFacts(UUID id, SideTask task) {
            return new DeathRecoveryFacts(drops, false, false, true,
                    drops == DeathDropState.DROPS_ABSENT
                            ? "loaded area has no tracked entity" : "bounded fixture observation");
        }
    }

    private static SideTaskEngine engine(Port port, Map<String, String> data,
                                          List<Map<String, Object>> terminals) {
        var events = new ArrayList<String>();
        var engine = new SideTaskEngine((event, payload) -> {
            events.add(event);
            if (event.equals("side_task_skipped") || event.equals("side_task_completed")) {
                terminals.add(payload);
            }
        }, token -> token);
        engine.registerType(new DeathRecoverySideTaskType());
        assertTrue(engine.startNow(new DeathRecoverySideTaskType(), ID, port, null, data));
        return engine;
    }

    @Test void emptyDeathEventStillCreatesProtectedSideTaskWithUnknownItems() {
        DeathRecoverySideTaskTrigger.resetForTest();
        var port = new Port();
        var engine = new SideTaskEngine((event, payload) -> { }, token -> token);
        engine.registerType(new DeathRecoverySideTaskType());
        engine.registerTrigger(new DeathRecoverySideTaskTrigger());
        DeathRecoverySideTaskTrigger.expect(ID, 93, "minecraft:overworld", "10,64,20", Map.of());

        engine.tick(ID, port, null, true);

        assertTrue(engine.isActive(ID), "the death event must open its protected path even with an empty item table");
        assertEquals("UNKNOWN", engine.current(ID).orElseThrow().data().get("lost_items_state"));
        assertEquals(0, DeathRecoverySideTaskTrigger.pendingCount(ID));
    }

    @Test void emptyBatchCannotTurnNoTrackedEntityIntoConfirmedAbsence() {
        var port = new Port();
        var terminals = new ArrayList<Map<String, Object>>();
        var engine = engine(port, Map.of("death_seq", "93"), terminals);

        engine.tick(ID, port, null, false);

        assertTrue(engine.isActive(ID), "empty item evidence must keep the protected recovery path active");
        assertEquals(SideTaskWorldPort.DeathDropState.UNKNOWN.name(),
                engine.current(ID).orElseThrow().data().get("drop_state"));
        assertTrue(terminals.isEmpty(), "UNKNOWN must not settle as drops_absent_with_world_evidence");
    }

    @Test void emptyBatchProjectsUnknownInsteadOfNoLossOrPermanentLoss() {
        var port = new Port();
        var engine = engine(port, Map.of("death_seq", "93"), new ArrayList<>());
        var task = engine.current(ID).orElseThrow();
        String context = new DeathRecoverySideTaskType().renderContext(task, port);

        assertTrue(context.contains("lost_items_state=\"UNKNOWN\""), context);
        assertTrue(context.contains("逐项损失未知"), context);
        assertTrue(context.contains("不得把空表解释为无损失或永久丢失"), context);
        assertFalse(context.contains("已永久丢失"));
    }

    @Test void nonemptyBatchStillUsesRealAbsenceEvidence() {
        var port = new Port();
        var terminals = new ArrayList<Map<String, Object>>();
        var engine = engine(port, Map.of("death_seq", "93", "lost_items", "minecraft:iron_sword=1"), terminals);

        engine.tick(ID, port, null, false);

        assertFalse(engine.isActive(ID));
        assertEquals("drops_absent_with_world_evidence", terminals.get(0).get("deathRecoveryTerminalReason"));
    }
}
