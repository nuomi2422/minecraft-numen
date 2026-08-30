package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RddChainFactoryTest {
    private static final UUID CID = UUID.fromString("c29c5c40-0632-4982-9ab7-d99528d58e17");

    @Test void buildsUsableChain() {
        Goal goal = RddChainFactory.fromObjective(CID, "找 3 颗钻石");
        assertEquals(1, goal.primaryGoals().size());
        PrimaryGoal primary = goal.primaryGoals().get(0);
        assertEquals("找 3 颗钻石", primary.description());
        assertEquals(1, primary.subtasks().size());
        Subtask subtask = primary.subtasks().get(0);
        assertEquals(DetectionMode.HARD_CODED, subtask.detectionMode());
        assertFalse(subtask.condition().isEmpty());
        TaskChain chain = new TaskChain(goal);
        chain.startCurrent();
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
    }

    @Test void trimsAndRejectsBlank() {
        assertThrows(IllegalArgumentException.class,
                () -> RddChainFactory.fromObjective(CID, "   "));
        assertThrows(IllegalArgumentException.class,
                () -> RddChainFactory.fromObjective(CID, null));
        assertEquals("ok", RddChainFactory.fromObjective(CID, "  ok  ").description());
    }

    @Test void capsObjectiveLength() {
        String huge = "x".repeat(5000);
        Goal goal = RddChainFactory.fromObjective(CID, huge);
        assertEquals(RddChainFactory.MAX_OBJECTIVE_CHARS, goal.description().length());
    }
}
