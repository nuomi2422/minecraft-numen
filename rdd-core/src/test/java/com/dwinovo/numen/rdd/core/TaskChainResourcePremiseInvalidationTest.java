package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Death/LOST invalidates only resource-dependent work; completed facts and world history stay intact. */
class TaskChainResourcePremiseInvalidationTest {
    private static final String PICKAXE = "minecraft:diamond_pickaxe";
    private static final String EMERALD = "minecraft:emerald";

    private static Subtask task(String id, String assetKey, List<ResourceUse> uses) {
        return new Subtask(id, "do " + id, DetectionMode.HARD_CODED,
                Map.of("asset_key", assetKey, "minimum", 1), 0, 0, false, null,
                NodeType.MAIN, null, uses);
    }

    private static TaskChain chain(PrimaryGoal... primaries) {
        return new TaskChain(new Goal("g", "goal", List.of(primaries)));
    }

    @Test void exactLossInvalidatesDependentCurrentWorkButPreservesCompletedHistory() {
        Subtask done = task("done", EMERALD, List.of());
        Subtask affected = task("affected", PICKAXE,
                List.of(new ResourceUse(PICKAXE, 1, ResourceUse.Kind.RETAIN)));
        Subtask unaffected = task("unaffected", EMERALD, List.of());
        TaskChain c = chain(new PrimaryGoal("p", "current", List.of(done, affected, unaffected)));
        c.startCurrent();
        assertTrue(c.applyHardCodedResult("done", true));
        c.startCurrentWithCounts(Map.of(PICKAXE, 1));
        c.bindExecution("affected", "old-body-execution");
        c.recordSatisfiedNeed("p|r0|a1|" + PICKAXE);
        String goalBefore = c.goal().toString();
        long version = c.stateVersion();

        var result = c.invalidateResourcePremises(Set.of(PICKAXE), "death snapshot lost tool");

        assertTrue(result.reevaluationRequired());
        assertEquals(List.of("p"), result.invalidatedPrimaryIds());
        assertEquals(List.of("affected"), result.invalidatedSubtaskIds());
        assertEquals(SubtaskStatus.COMPLETED, c.subtaskStatuses().get("done"));
        assertEquals(SubtaskStatus.INVALIDATED, c.subtaskStatuses().get("affected"));
        assertEquals(SubtaskStatus.PENDING, c.subtaskStatuses().get("unaffected"));
        assertEquals(PrimaryGoalStatus.REPLANNING, c.primaryStatus());
        assertNull(c.acquireBaseline("affected"));
        assertNull(c.activeExecutionId());
        assertFalse(c.isSatisfiedNeed("p|r0|a1|" + PICKAXE));
        assertEquals(goalBefore, c.goal().toString(), "goal specification and real history are not rewritten");
        assertTrue(c.stateVersion() > version);
        assertThrows(IllegalStateException.class, c::resumeFromReplanning,
                "resource-invalidated plan must not silently unfreeze");
    }

    @Test void lostWaitForInvalidatesOnlyUnfinishedStepsOfThatPrimary() {
        TaskChain c = chain(
                new PrimaryGoal("prior", "verified base", List.of(task("base", EMERALD, List.of()))),
                new PrimaryGoal("p", "needs pickaxe", List.of(task("s1", EMERALD, List.of()), task("s2", EMERALD, List.of())),
                        List.of(new AssetRequirement(PICKAXE, 1))));
        c.settlePrimaryByFact("prior", "verified world fact", true);
        Set<String> history = c.satisfiedStages();
        String pending = c.toJson();
        assertTrue(c.wouldInvalidateResourcePremises(Set.of(PICKAXE), false));
        assertEquals(pending, c.toJson(), "loss projection must not half-write an unadmitted plan");
        assertThrows(IllegalStateException.class, () -> c.invalidateResourcePremises(Set.of(PICKAXE)),
                "resource premises commit only after death replan admission");
        assertEquals(pending, c.toJson());
        c.startCurrent();

        var result = c.invalidateResourcePremises(Set.of(PICKAXE));

        assertTrue(result.reevaluationRequired());
        assertEquals(List.of("s1", "s2"), result.invalidatedSubtaskIds());
        assertEquals(PrimaryGoalStatus.REPLANNING, c.primaryStatus(), "admitted loss freezes the old plan");
        assertTrue(c.resourcePremiseReevaluationRequired());
        assertEquals(history, c.satisfiedStages(), "true base/completion facts survive local invalidation");
        assertEquals(List.of(new AssetRequirement(PICKAXE, 1)), c.currentPrimary().waitFor(),
                "declaration remains visible for replanning; only its old premise is invalidated");
    }

    @Test void recoveringLossWaitsForHostAdmissionWithoutPartialWrite() {
        TaskChain active = chain(new PrimaryGoal("p", "current", List.of(task("s1", PICKAXE, List.of()))));
        active.startCurrent();
        TaskChain recovering = TaskChain.fromJson(active.toJson());
        assertEquals(PrimaryGoalStatus.RECOVERING, recovering.primaryStatus());
        String before = recovering.toJson();

        assertTrue(recovering.wouldInvalidateResourcePremises(Set.of(PICKAXE), false));
        assertThrows(IllegalStateException.class, () -> recovering.invalidateResourcePremises(Set.of(PICKAXE)));
        assertEquals(before, recovering.toJson(), "restored old chain stays untouched until real resume/replan admission");
    }

    @Test void unrelatedLossDoesNotForceReplanOrTouchStatuses() {
        TaskChain c = chain(new PrimaryGoal("p", "current", List.of(task("s1", EMERALD, List.of()))));
        c.startCurrent();
        String before = c.toJson();
        long version = c.stateVersion();

        var result = c.invalidateResourcePremises(Set.of(PICKAXE));

        assertFalse(result.reevaluationRequired());
        assertTrue(result.invalidatedSubtaskIds().isEmpty());
        assertEquals(before, c.toJson());
        assertEquals(version, c.stateVersion());
        assertEquals(PrimaryGoalStatus.ACTIVE, c.primaryStatus());
        assertEquals(SubtaskStatus.RUNNING, c.currentSubtaskStatus());
    }

    @Test void unknownDeathScopeInvalidatesAllUnfinishedResourcePremisesExplicitly() {
        TaskChain c = chain(new PrimaryGoal("p", "current", List.of(
                task("s1", EMERALD, List.of()), task("s2", PICKAXE, List.of()))));
        String pending = c.toJson();
        assertTrue(c.wouldInvalidateResourcePremises(Set.of(), true));
        assertEquals(pending, c.toJson());
        c.startCurrent();

        var result = c.invalidateAllResourcePremises("death inventory UNKNOWN");

        assertTrue(result.unknownScope());
        assertTrue(result.reevaluationRequired());
        assertEquals(List.of("s1", "s2"), result.invalidatedSubtaskIds());
        assertTrue(c.resourcePremiseReevaluationRequired());
    }

    @Test void groupConditionIsAffectedByAnyLostMember() {
        Subtask group = Subtask.hardCoded("food", "gather food", Map.of("group", "food", "minimum", 3));
        TaskChain c = chain(new PrimaryGoal("p", "current", List.of(group)));
        c.startCurrent();

        var result = c.invalidateResourcePremises(Set.of("minecraft:bread"));

        assertTrue(result.reevaluationRequired());
        assertEquals(List.of("food"), result.invalidatedSubtaskIds());
    }

    @Test void invalidationSurvivesRestartAndOnlyPlanReplacementClearsIt() {
        TaskChain c = chain(new PrimaryGoal("p", "current", List.of(task("s1", PICKAXE, List.of()))));
        c.startCurrent();
        c.invalidateResourcePremises(Set.of(PICKAXE), "death");

        TaskChain restored = TaskChain.fromJson(c.toJson());

        assertEquals(PrimaryGoalStatus.REPLANNING, restored.primaryStatus());
        assertEquals(SubtaskStatus.INVALIDATED, restored.currentSubtaskStatus());
        assertTrue(restored.resourcePremiseReevaluationRequired());
        assertThrows(IllegalStateException.class, restored::resumeFromReplanning);
        restored.replaceCurrentSubtasks(List.of(task("s1-r1", EMERALD, List.of())));
        assertFalse(restored.resourcePremiseReevaluationRequired());
        assertEquals(SubtaskStatus.PENDING, restored.currentSubtaskStatus());
    }

    @Test void emptyKnownLossIsANoopNotAnUnknownDeath() {
        TaskChain c = chain(new PrimaryGoal("p", "current", List.of(task("s1", EMERALD, List.of()))));
        String before = c.toJson();

        var result = c.invalidateResourcePremises(Set.of());

        assertFalse(result.unknownScope());
        assertFalse(result.reevaluationRequired());
        assertEquals(before, c.toJson());
    }
}
