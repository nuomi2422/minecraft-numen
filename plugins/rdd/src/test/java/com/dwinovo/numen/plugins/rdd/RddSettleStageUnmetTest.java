package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RddSettleStageUnmetTest {
    private static Subtask item(String id) {
        return Subtask.hardCoded(id, id, Map.of("type", "item", "item", "minecraft:diamond", "minimum", 1));
    }

    @Test void finalGoalIsAddedOnlyWhenRequested() {
        Subtask a = item("a"); Subtask b = item("b");
        Goal goal = new Goal("g", "g", List.of(
                new PrimaryGoal("p1", "p1", List.of(a)), new PrimaryGoal("p2", "p2", List.of(b))));
        PrimaryGoal target = goal.primaryGoals().get(0);
        assertTrue(RddSettleStageTool.unmetConditions(target, goal, false, s -> s.id().equals("a")).isEmpty());
        assertEquals(List.of("final:b"),
                RddSettleStageTool.unmetConditions(target, goal, true, s -> s.id().equals("a")));
        assertEquals(List.of("a", "final:b"),
                RddSettleStageTool.unmetConditions(target, goal, true, s -> false));
        assertTrue(RddSettleStageTool.unmetConditions(null, goal, false, s -> false).isEmpty());
    }
}
