package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
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
        assertEquals(List.of("no_target"), RddSettleStageTool.unmetConditions(null, goal, false, s -> false));
    }

    @Test void onlyTheLastStageEndsTheGoal() {
        PrimaryGoal a = new PrimaryGoal("p1", "first", List.of(item("a")));
        PrimaryGoal b = new PrimaryGoal("p2", "last", List.of(item("b")));
        TaskChain chain = new TaskChain(new Goal("g", "g", List.of(a, b)));
        assertFalse(RddSettleStageTool.isFinalStage(chain, "p1"));
        assertTrue(RddSettleStageTool.isFinalStage(chain, "p2"));
        assertFalse(RddSettleStageTool.isFinalStage(chain, "p9"));
        assertFalse(RddSettleStageTool.isFinalStage(chain, ""));
        assertFalse(RddSettleStageTool.isFinalStage(null, "p2"));
    }

    @Test void unexpandedFinalCannotBeVacuouslyVerified() {
        PrimaryGoal target = new PrimaryGoal("p1", "target", List.of(item("a")));
        PrimaryGoal finalGoal = PrimaryGoal.unexpanded("p2", "final goal not yet expanded", null);
        Goal goal = new Goal("g", "g", List.of(target, finalGoal));
        assertEquals(List.of("final:no_conditions"),
                RddSettleStageTool.unmetConditions(target, goal, true, sub -> true));
        assertTrue(RddSettleStageTool.unmetConditions(target, goal, false, sub -> true).isEmpty());
    }

    @Test void missingFinalAndThrowingPredicateFailClosed() {
        Goal one = new Goal("g", "g", List.of(new PrimaryGoal("p", "p", List.of(item("a")))));
        assertEquals(List.of("no_final_goal"),
                RddSettleStageTool.unmetConditions(one.primaryGoals().get(0), null, true, s -> true));
        Predicate<Subtask> boom = s -> { throw new IllegalStateException("boom"); };
        assertEquals(List.of("a"),
                RddSettleStageTool.unmetConditions(one.primaryGoals().get(0), one, false, boom));
    }
}
