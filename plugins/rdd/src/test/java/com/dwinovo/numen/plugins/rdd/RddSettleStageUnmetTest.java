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

    @Test void settlingOneStageLeavesOtherStagesDependenciesAlone() {
        PrimaryGoal a = new PrimaryGoal("p1", "iron stage", List.of(item("iron")));
        PrimaryGoal b = new PrimaryGoal("p2", "diamond stage", List.of(item("diamond")));
        TaskChain chain = new TaskChain(new Goal("g4", "g4", List.of(a, b)));
        chain.startCurrent();
        // p2 becomes unnecessary; that must not mark p1's own step as skipped or done.
        chain.settlePrimaryByFact("p2", "总目标已达成，钻石这一级不需要");
        assertEquals(com.dwinovo.numen.rdd.api.SubtaskStatus.RUNNING, chain.subtaskStatuses().get("iron"),
                "the current stage keeps running; settling a future stage does not stop it");
        assertEquals(com.dwinovo.numen.rdd.api.SubtaskStatus.PENDING, chain.subtaskStatuses().get("diamond"),
                "a future stage settled by fact records a reason; it is not executed");
        assertEquals("p1", chain.currentPrimary().id(), "cursor must not jump over unfinished work");
        assertTrue(chain.settledStages().containsKey("p2"));
        assertFalse(chain.settledStages().containsKey("p1"));
    }

    @Test void unverifiedSettlementSkipsButIsNotACompletionFact() {
        PrimaryGoal a = new PrimaryGoal("p1", "iron stage", List.of(item("iron")));
        PrimaryGoal b = new PrimaryGoal("p2", "later stage", List.of(item("later")));
        TaskChain chain = new TaskChain(new Goal("g5", "g5", List.of(a, b)));
        chain.startCurrent();
        // Planner says the current stage is unnecessary, with no verified world facts.
        chain.settlePrimaryByFact("p1", "改用现成装备，不再单独做这一步", false);
        assertTrue(chain.satisfiedStages().isEmpty(),
                "an unverified waiver must not become a completion fact");
        assertEquals(1, chain.waivedStages().size(), "but the waiver is recorded for this chain");
        assertEquals("p2", chain.currentPrimary().id(), "a waiver still advances past the stage");

        // A verified settlement does write the completion fact.
        TaskChain verifiedChain = new TaskChain(new Goal("g6", "g6", List.of(a, b)));
        verifiedChain.startCurrent();
        verifiedChain.settlePrimaryByFact("p1", "钻石镐已在背包", true);
        assertEquals(1, verifiedChain.satisfiedStages().size(),
                "verified settlement is inherited as completion");
        assertTrue(verifiedChain.waivedStages().isEmpty());
    }

    @Test void waivedStageSurvivesRestartWithoutBecomingCompletion() {
        PrimaryGoal a = new PrimaryGoal("p1", "iron stage", List.of(item("iron")));
        PrimaryGoal b = new PrimaryGoal("p2", "later stage", List.of(item("later")));
        TaskChain chain = new TaskChain(new Goal("g7", "g7", List.of(a, b)));
        chain.startCurrent();
        chain.settlePrimaryByFact("p1", "这条链不需要这一步", false);
        TaskChain restored = TaskChain.fromJson(chain.toJson());
        assertTrue(restored.satisfiedStages().isEmpty(), "restart must not upgrade a waiver");
        assertEquals(1, restored.waivedStages().size());
    }

    @Test void repeatedSettlementIsDetected() {
        PrimaryGoal a = new PrimaryGoal("p1", "first", List.of(item("a")));
        PrimaryGoal b = new PrimaryGoal("p2", "future", List.of(item("b")));
        TaskChain chain = new TaskChain(new Goal("g3", "g3", List.of(a, b)));
        assertFalse(RddSettleStageTool.alreadySettled(chain, "p2"));
        chain.startCurrent();
        chain.settlePrimaryByFact("p2", "未来阶段不再需要");
        assertTrue(RddSettleStageTool.alreadySettled(chain, "p2"),
                "a second identical report must be refused, not counted twice");
        assertFalse(RddSettleStageTool.alreadySettled(chain, "p1"));
        assertFalse(RddSettleStageTool.alreadySettled(null, "p2"));
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

    @Test void singleStageChainIsBothFirstAndFinal() {
        TaskChain one = new TaskChain(new Goal("g1", "g1", List.of(
                new PrimaryGoal("only", "only stage", List.of(item("a"))))));
        assertTrue(RddSettleStageTool.isFinalStage(one, "only"),
                "settling the only stage must also close the goal");
    }

    @Test void lastStageStaysFinalWhenEarlierStagesRemainUnsatisfied() {
        PrimaryGoal a = new PrimaryGoal("p1", "first", List.of(item("a")));
        PrimaryGoal b = new PrimaryGoal("p2", "second", List.of(item("b")));
        TaskChain chain = new TaskChain(new Goal("g2", "g2", List.of(a, b)));
        // The cursor sitting on p1 must not make p2 stop being the goal-ending stage.
        assertTrue(RddSettleStageTool.isFinalStage(chain, "p2"));
        assertFalse(RddSettleStageTool.isFinalStage(chain, "p1"));
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
