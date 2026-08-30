package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TaskChainTest {
    @Test void hardCodedSubtaskAdvancesWithoutSupervisor() {
        var primary = new PrimaryGoal("p", "prepare", java.util.List.of(
                Subtask.hardCoded("s1", "get stone", Map.of("item", "stone")),
                Subtask.hardCoded("s2", "get wood", Map.of("item", "wood"))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s1", true));
        assertEquals("s2", chain.currentSubtask().id());
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s2", true));
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, chain.primaryStatus());
        assertThrows(IllegalStateException.class, () -> chain.applyHardCodedResult("s2", true));
    }

    @Test void multiSubtaskStaysActiveUntilLastCompletes() {
        var primary = new PrimaryGoal("p", "prepare", java.util.List.of(
                Subtask.hardCoded("s1", "get stone", Map.of("asset_key", "minecraft:stone", "minimum", 1)),
                Subtask.hardCoded("s2", "get wood", Map.of("asset_key", "minecraft:oak_log", "minimum", 1))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s1", true));
        assertEquals("s2", chain.currentSubtask().id());
        assertEquals(PrimaryGoalStatus.ACTIVE, chain.primaryStatus()); // 未到最后一级仍 ACTIVE
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s2", true));
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, chain.primaryStatus());
    }

    @Test void supervisorMustMatchAwaitingPrimary() {
        var primary = new PrimaryGoal("p", "prepare", java.util.List.of(
                Subtask.hardCoded("s", "get stone", Map.of("item", "stone"))));
        var chain = new TaskChain(new Goal("g", "goal", java.util.List.of(primary)));
        chain.startCurrent();
        chain.applyHardCodedResult("s", true);
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "p", "observed"));
        assertEquals(PrimaryGoalStatus.COMPLETED, chain.primaryStatus());
    }
}
