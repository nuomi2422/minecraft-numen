package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class TaskChainSkipTest {
    @Test void optionalFailureCanBeSkippedWithoutRetryLoop() {
        var first = Subtask.hardCoded("food", "optional food",
                Map.of("asset_key", "minecraft:carrot", "minimum", 8));
        var next = Subtask.hardCoded("next", "continue",
                Map.of("asset_key", "minecraft:iron_ingot", "minimum", 1));
        var chain = new TaskChain(new Goal("g", "goal", List.of(
                new PrimaryGoal("p", "phase", List.of(first, next)))));
        chain.startCurrent();
        chain.markFailed("food", "unreachable");
        chain.skipSubtask("food", "optional food unavailable");
        assertEquals("next", chain.currentSubtask().id());
        assertEquals(SubtaskStatus.PENDING, chain.currentSubtaskStatus());
        assertEquals(SubtaskStatus.COMPLETED, chain.subtaskStatuses().get("food"));
    }
}
