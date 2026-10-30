package com.dwinovo.numen.rdd.side.death;

import com.dwinovo.numen.rdd.side.SideTask;
import com.dwinovo.numen.rdd.side.SideTaskState;
import com.dwinovo.numen.rdd.side.SideTaskType;
import com.dwinovo.numen.rdd.side.SideTaskVerdict;
import com.dwinovo.numen.rdd.side.SideTaskWorldPort;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeathRecoverySideTaskTypeTest {

    private static final UUID C = UUID.fromString("00000000-0000-0000-0000-0000000000dc");

    private static final class Port implements SideTaskWorldPort {
        long time = 1000L;
        DeathRecoveryFacts facts = DeathRecoveryFacts.unknown("not observed");

        @Override public long gameTime() { return time; }
        @Override public long dayTime() { return 1000L; }
        @Override public long gameDay() { return 0L; }
        @Override public boolean hasDayNightCycle() { return true; }
        @Override public String worldId() { return "minecraft:overworld"; }
        @Override public boolean isSleeping(UUID companionId) { return false; }
        @Override public boolean isDay() { return true; }
        @Override public Wake pollWake(UUID companionId) { return Wake.NONE; }
        @Override public DeathRecoveryFacts deathRecoveryFacts(UUID companionId, SideTask task) { return facts; }
    }

    private static SideTask task() {
        return new SideTask(C, "death_recovery-1", DeathRecoverySideTaskType.TYPE_ID,
                1L, 0L, 1000L, 1000L + DeathRecoverySideTaskType.DEADLINE_TICKS,
                SideTaskState.ACTIVE, DeathRecoverySideTaskType.PHASE_DECIDE,
                Map.of("death_seq", "7", "dimension", "minecraft:the_nether",
                        "death_at", "10,64,20"), null);
    }

    @Test
    void unknownFactsKeepBranchOpen() {
        DeathRecoverySideTaskType type = new DeathRecoverySideTaskType();
        SideTaskVerdict verdict = type.evaluate(task(), new Port());
        assertEquals(SideTaskVerdict.Kind.CONTINUE, verdict.kind());
        assertEquals("UNKNOWN", verdict.data().get("drop_state"));
    }

    @Test
    void unloadedChunkIsNotLoss() {
        Port port = new Port();
        port.facts = new SideTaskWorldPort.DeathRecoveryFacts(
                SideTaskWorldPort.DeathDropState.CHUNK_UNLOADED, false, false, true,
                "drop chunk is not loaded");
        SideTaskVerdict verdict = new DeathRecoverySideTaskType().evaluate(task(), port);
        assertEquals(SideTaskVerdict.Kind.CONTINUE, verdict.kind());
    }

    @Test
    void confirmedAbsenceSkipsForLossAnalysis() {
        Port port = new Port();
        port.facts = new SideTaskWorldPort.DeathRecoveryFacts(
                SideTaskWorldPort.DeathDropState.DROPS_ABSENT, false, false, true,
                "loaded scan found no tracked drops");
        SideTaskVerdict verdict = new DeathRecoverySideTaskType().evaluate(task(), port);
        assertEquals(SideTaskVerdict.Kind.SKIP, verdict.kind());
        assertTrue(verdict.evidence().contains("不可回收"));
    }

    @Test
    void recoveredDropsCompleteBeforeDeadline() {
        Port port = new Port();
        port.facts = new SideTaskWorldPort.DeathRecoveryFacts(
                SideTaskWorldPort.DeathDropState.RECOVERED, false, false, true,
                "all tracked items observed");
        SideTaskVerdict verdict = new DeathRecoverySideTaskType().evaluate(task(), port);
        assertEquals(SideTaskVerdict.Kind.COMPLETE, verdict.kind());
    }

    @Test
    void businessDeadlineDoesNotClaimPhysicalLoss() {
        Port port = new Port();
        port.time = 1000L + DeathRecoverySideTaskType.DEADLINE_TICKS + 1L;
        SideTaskVerdict verdict = new DeathRecoverySideTaskType().evaluate(task(), port);
        assertEquals(SideTaskVerdict.Kind.SKIP, verdict.kind());
        assertTrue(verdict.evidence().contains("UNKNOWN"));
    }

    @Test
    void renderContextExplainsDecisionBeforeRecovery() {
        String context = new DeathRecoverySideTaskType().renderContext(task(), new Port());
        assertTrue(context.contains("备用"));
        assertTrue(context.contains("rdd_decline_side"));
        assertTrue(context.contains("区块未加载"));
    }

    @Test
    void triggerAllowedDuringDeathOccupancy() {
        assertTrue(new DeathRecoverySideTaskTrigger().allowedWhileDeathOccupying());
    }
}
