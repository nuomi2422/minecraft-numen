package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-12: DAILY keeps its source and schedule, but must not occupy main dispatch.
 * Uses the production chain instead of a copied selector or mocked state machine.
 * All-DAILY plans must be rejected without creating verified stage facts.
 */
class TaskChainDailyDispatchTest {
    private static final RecurrenceSpec SCHEDULE = RecurrenceSpec.daily(true);

    private static Subtask daily(String id) {
        return new Subtask(id, "daily " + id, DetectionMode.HARD_CODED,
                Map.of("asset_key", "minecraft:wheat", "minimum", 1),
                0, 0, false, null, NodeType.DAILY, SCHEDULE);
    }

    private static Subtask main(String id) {
        return Subtask.hardCoded(id, "main " + id,
                Map.of("asset_key", "minecraft:iron_ingot", "minimum", 1));
    }

    private static TaskChain chain(Subtask... steps) {
        return new TaskChain(new Goal("g", "iron equipment",
                List.of(new PrimaryGoal("p", "prepare", List.of(steps)))));
    }

    private static void assertDailyRetained(TaskChain chain, String id) {
        Subtask source = chain.currentPrimary().subtasks().stream()
                .filter(s -> s.id().equals(id)).findFirst().orElseThrow();
        assertEquals(NodeType.DAILY, source.nodeType());
        assertEquals(SCHEDULE, source.recurrence());
        assertEquals(SubtaskStatus.SKIPPED, chain.subtaskStatuses().get(id));
        assertTrue(chain.skipReason(id).contains("recurring"));
        assertFalse(chain.pendingSubtasks().stream().anyMatch(sub -> sub.id().equals(id)));
        assertNotEquals(SubtaskStatus.RUNNING, chain.subtaskStatuses().get(id),
                "recurring source must not be dispatched as main");
        assertNotEquals(SubtaskStatus.COMPLETED, chain.subtaskStatuses().get(id),
                "passing a recurring source must not invent completion");
    }

    @Test void dailyFirstActivationSelectsMainWithoutRemovingSource() {
        TaskChain chain = chain(daily("daily"), main("main"));
        assertTrue(chain.activateCurrent(Map.of()));
        assertEquals("main", chain.currentSubtask().id(),
                "daily-first plan must dispatch the main node");
        assertEquals(2, chain.currentPrimary().subtasks().size());
        assertDailyRetained(chain, "daily");
    }

    @Test void activatingMixedPlanNeverMarksDailyRunning() {
        TaskChain chain = chain(daily("daily"), main("main"));
        chain.activateCurrent(Map.of());
        assertDailyRetained(chain, "daily");
    }

    @Test void consecutiveDailyBetweenMainNodesDoNotBlockAdvance() {
        TaskChain chain = chain(main("first"), daily("d1"), daily("d2"), main("second"));
        chain.activateCurrent(Map.of());
        assertTrue(chain.applyHardCodedResult("first", true));
        assertEquals("second", chain.currentSubtask().id(),
                "completion of first MAIN must pass both DAILY sources");
        assertEquals(PrimaryGoalStatus.ACTIVE, chain.primaryStatus());
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("second", true));
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, chain.primaryStatus());
        assertDailyRetained(chain, "d1");
        assertDailyRetained(chain, "d2");
    }

    @Test void trailingDailyDoesNotRequireFakeCompletionBeforeSupervisor() {
        TaskChain chain = chain(main("main"), daily("d1"), daily("d2"));
        chain.activateCurrent(Map.of());
        assertTrue(chain.applyHardCodedResult("main", true));
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, chain.primaryStatus(),
                "trailing DAILY must not hold completed MAIN work open");
        assertDailyRetained(chain, "d1");
        assertDailyRetained(chain, "d2");
    }

    @Test void pendingJsonRoundTripDoesNotPutDailyBackOnMainRoute() {
        TaskChain original = chain(daily("daily"), main("main"));
        TaskChain restored = TaskChain.fromJson(original.toJson());
        assertTrue(restored.activateCurrent(Map.of()));
        assertEquals("main", restored.currentSubtask().id());
        assertDailyRetained(restored, "daily");
    }

    @Test void activeJsonRoundTripRecoversMainInsteadOfDaily() {
        TaskChain original = chain(daily("daily"), main("main"));
        original.activateCurrent(Map.of());
        TaskChain restored = TaskChain.fromJson(original.toJson());
        assertEquals(PrimaryGoalStatus.RECOVERING, restored.primaryStatus());
        restored.resumeFromRecovering();
        assertEquals("main", restored.currentSubtask().id());
        assertEquals(SubtaskStatus.RUNNING, restored.currentSubtaskStatus());
        assertDailyRetained(restored, "daily");
    }

    @Test void oldDailyReceiptCannotCompleteOrAdvanceMain() {
        TaskChain chain = chain(daily("daily"), main("main"));
        chain.activateCurrent(Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> chain.applyHardCodedResult("daily", true),
                "a DAILY receipt is not a receipt for current MAIN");
        assertEquals("main", chain.currentSubtask().id());
        assertEquals(SubtaskStatus.RUNNING, chain.subtaskStatuses().get("main"));
        assertDailyRetained(chain, "daily");
    }

    @Test void legacyJsonWithoutNodeTypeStillDispatchesMain() {
        TaskChain original = chain(main("legacy"));
        var json = com.google.gson.JsonParser.parseString(original.toJson()).getAsJsonObject();
        for (var primary : json.getAsJsonObject("goal").getAsJsonArray("primaryGoals")) {
            for (var subtask : primary.getAsJsonObject().getAsJsonArray("subtasks")) {
                subtask.getAsJsonObject().remove("nodeType");
            }
        }
        String oldJson = json.toString();
        assertFalse(oldJson.contains("nodeType"));
        TaskChain restored = TaskChain.fromJson(oldJson);
        assertEquals(NodeType.MAIN, restored.currentSubtask().nodeType());
        assertTrue(restored.activateCurrent(Map.of()));
        assertEquals("legacy", restored.currentSubtask().id());
        assertTrue(restored.applyHardCodedResult("legacy", true));
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, restored.primaryStatus());
    }

    @Test void consecutiveLeadingDailyNodesDoNotBlockFirstMain() {
        TaskChain chain = chain(daily("d1"), daily("d2"), main("main"));
        assertTrue(chain.activateCurrent(Map.of()));
        assertEquals("main", chain.currentSubtask().id(),
                "all leading DAILY sources must be bypassed by main dispatch");
        assertEquals(3, chain.currentPrimary().subtasks().size());
        assertDailyRetained(chain, "d1");
        assertDailyRetained(chain, "d2");
    }

    @Test void registryActivationUsesMainRoute() {
        TaskChain chain = chain(daily("daily"), main("main"));
        assertTrue(chain.activateCurrentWithRegistry(new AssetRegistry()));
        assertEquals("main", chain.currentSubtask().id());
        assertEquals(1, chain.attempts("main"));
        assertDailyRetained(chain, "daily");
    }

    @Test void snapshotActivationUsesMainRoute() {
        TaskChain chain = chain(daily("daily"), main("main"));
        assertTrue(chain.activateCurrentWithSnapshot(PlanningAssetSnapshot.from(Map.of(), new AssetRegistry())));
        assertEquals("main", chain.currentSubtask().id());
        assertEquals(1, chain.attempts("main"));
        assertDailyRetained(chain, "daily");
    }

    @Test void directStartAndCountsStartUseMainRoute() {
        TaskChain direct = chain(daily("daily"), main("main"));
        direct.startCurrent();
        assertEquals("main", direct.currentSubtask().id());
        assertDailyRetained(direct, "daily");
        TaskChain counted = chain(daily("daily"), main("main"));
        counted.startCurrentWithCounts(Map.of());
        assertEquals("main", counted.currentSubtask().id());
        assertEquals(1, counted.attempts("main"));
        assertDailyRetained(counted, "daily");
    }

    @Test void allDailyExpansionIsRejectedWithoutStageCompletion() {
        TaskChain chain = new TaskChain(new Goal("g", "iron equipment",
                List.of(PrimaryGoal.unexpanded("p", "prepare", List.of()))));
        assertThrows(IllegalArgumentException.class, () -> chain.expandCurrentPrimary(List.of(daily("daily"))));
        assertTrue(chain.currentPrimary().unexpanded());
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
        assertTrue(chain.satisfiedStages().isEmpty());
        assertThrows(IllegalStateException.class, () -> chain.activateCurrent(Map.of()));
    }

    @Test void allDailyReplacementIsRejectedAndOriginalPlanSurvives() {
        TaskChain chain = chain(main("main"));
        chain.activateCurrent(Map.of());
        chain.enterReplanningFromNegotiation("new plan");
        assertThrows(IllegalArgumentException.class, () -> chain.replaceCurrentSubtasks(List.of(daily("daily"))));
        assertEquals(PrimaryGoalStatus.REPLANNING, chain.primaryStatus());
        assertEquals(List.of(main("main")), chain.currentPrimary().subtasks());
        assertTrue(chain.satisfiedStages().isEmpty());
    }

    @Test void replacementAndReplanningResumeAndRestartKeepDailyOffMain() {
        TaskChain chain = chain(main("old"));
        chain.activateCurrent(Map.of());
        chain.enterReplanningFromNegotiation("new plan");
        chain.replaceCurrentSubtasks(List.of(daily("daily"), main("main")));
        chain.activateCurrent(Map.of());
        assertEquals("main", chain.currentSubtask().id());
        assertDailyRetained(chain, "daily");
        chain.enterReplanningFromNegotiation("retry");
        chain.resumeFromReplanning();
        chain.startCurrent();
        assertEquals("main", chain.currentSubtask().id());
        assertDailyRetained(chain, "daily");
        chain.resetForRestart("iron equipment again");
        chain.startCurrent();
        assertEquals("main", chain.currentSubtask().id());
        assertDailyRetained(chain, "daily");
    }

    @Test void legacyRunningDailyJsonRestoresPendingMainWithoutFakeCompletion() {
        TaskChain original = chain(daily("daily"), main("main"));
        var json = com.google.gson.JsonParser.parseString(original.toJson()).getAsJsonObject();
        json.addProperty("subtaskIndex", 0);
        json.addProperty("primaryStatus", "ACTIVE");
        json.getAsJsonObject("statuses").addProperty("daily", "RUNNING");
        TaskChain restored = TaskChain.fromJson(json.toString());
        assertEquals("main", restored.currentSubtask().id());
        assertEquals(SubtaskStatus.PENDING, restored.currentSubtaskStatus());
        assertNotEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, restored.primaryStatus());
        assertTrue(restored.satisfiedStages().isEmpty());
        assertDailyRetained(restored, "daily");
    }

    @Test void eagerAllDailyCannotBeActivatedOrVerified() {
        TaskChain chain = chain(daily("daily"));
        assertThrows(IllegalArgumentException.class, () -> chain.activateCurrent(Map.of()));
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
        assertTrue(chain.satisfiedStages().isEmpty());
    }

    @Test void patchCannotLeaveOnlyRecurringSources() {
        TaskChain chain = chain(daily("daily"), main("main"));
        PlanPatch patch = new PlanPatch("drop", "p", chain.stateVersion(),
                List.of(PlanPatch.Op.drop("main")), "daily is not proof of stage completion");
        assertFalse(chain.proposePatch(patch).ok());
        assertFalse(chain.commitPatch(patch, chain.stateVersion()).ok());
        assertEquals("main", chain.currentSubtask().id());
        assertTrue(chain.satisfiedStages().isEmpty());
    }

    @Test void sideNodeKeepsExistingMainlinePolicy() {
        Subtask side = new Subtask("side", "side work", DetectionMode.HARD_CODED,
                Map.of("asset_key", "minecraft:iron_ingot", "minimum", 1),
                0, 0, false, null, NodeType.SIDE, null);
        TaskChain chain = chain(daily("daily"), side, main("main"));
        chain.startCurrent();
        assertEquals("side", chain.currentSubtask().id());
        assertTrue(chain.applyHardCodedResult("side", true));
        assertEquals("main", chain.currentSubtask().id());
    }

    @Test void legacyAllDailyJsonRetainsGoalAndParksInsteadOfDroppingOrVerifyingIt() {
        for (PrimaryGoalStatus oldState : List.of(PrimaryGoalStatus.PENDING, PrimaryGoalStatus.ACTIVE,
                PrimaryGoalStatus.RECOVERING, PrimaryGoalStatus.AWAITING_SUPERVISOR)) {
            TaskChain chain = chain(daily("daily"));
            var json = com.google.gson.JsonParser.parseString(chain.toJson()).getAsJsonObject();
            json.addProperty("primaryStatus", oldState.name());
            json.getAsJsonObject("statuses").addProperty("daily", "RUNNING");
            TaskChain restored = TaskChain.fromJson(json.toString());
            assertGoalRetained(chain, restored);
            assertEquals(PrimaryGoalStatus.REPLANNING, restored.primaryStatus());
            assertNull(restored.currentSubtask());
            assertNull(restored.snapshot().get("currentSubtaskId"));
            assertTrue(restored.satisfiedStages().isEmpty());
            assertThrows(IllegalArgumentException.class, restored::resumeFromReplanning);
            assertThrows(IllegalArgumentException.class, restored::startCurrent);
            assertThrows(IllegalArgumentException.class, () -> restored.applyHardCodedResult("daily", true));
            TaskChain twice = TaskChain.fromJson(restored.toJson());
            assertGoalRetained(restored, twice);
            assertEquals(PrimaryGoalStatus.REPLANNING, twice.primaryStatus());
            restored.replaceCurrentSubtasks(List.of(daily("new-daily"), main("new-main")));
            restored.startCurrent();
            assertEquals("new-main", restored.currentSubtask().id());
            assertTrue(restored.satisfiedStages().isEmpty());
        }
    }

    @Test void legacyTrailingDailyCanOnlyAwaitSupervisorAfterRealMainCompletion() {
        TaskChain chain = chain(main("main"), daily("daily"));
        chain.startCurrent();
        chain.applyHardCodedResult("main", true);
        var json = com.google.gson.JsonParser.parseString(chain.toJson()).getAsJsonObject();
        json.addProperty("subtaskIndex", 1);
        json.addProperty("primaryStatus", "ACTIVE");
        json.getAsJsonObject("statuses").addProperty("daily", "RUNNING");
        TaskChain restored = TaskChain.fromJson(json.toString());
        assertEquals("main", restored.currentSubtask().id());
        assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, restored.primaryStatus());
        assertTrue(restored.satisfiedStages().isEmpty());
        assertDailyRetained(restored, "daily");
    }

    @Test void nextPrimaryAlsoStartsAtItsMainNode() {
        TaskChain chain = new TaskChain(new Goal("g", "equipment", List.of(
                new PrimaryGoal("first", "first", List.of(main("m1"))),
                new PrimaryGoal("second", "second", List.of(daily("daily"), main("m2"))))));
        chain.startCurrent();
        chain.applyHardCodedResult("m1", true);
        chain.applySupervisorDecision(new SupervisorDecision(SupervisorDecisionType.CONFIRM, "first", "verified"));
        chain.startCurrent();
        assertEquals("m2", chain.currentSubtask().id());
        assertDailyRetained(chain, "daily");
    }

    @Test void pastDailyExecutionHistoryAndStageFactsSurviveRestoreAndAdvance() {
        TaskChain original = new TaskChain(new Goal("history", "equipment", List.of(
                new PrimaryGoal("past", "past stage", List.of(daily("done"), daily("failed"), daily("skipped"))),
                new PrimaryGoal("current", "current stage", List.of(main("m1"), main("m2"))))));
        var json = com.google.gson.JsonParser.parseString(original.toJson()).getAsJsonObject();
        json.addProperty("primaryIndex", 1);
        json.addProperty("subtaskIndex", 0);
        json.addProperty("primaryStatus", "ACTIVE");
        var statuses = json.getAsJsonObject("statuses");
        statuses.addProperty("done", "COMPLETED");
        statuses.addProperty("failed", "FAILED");
        statuses.addProperty("skipped", "SKIPPED");
        statuses.addProperty("m1", "RUNNING");
        json.getAsJsonObject("skipReasons").remove("done");
        json.getAsJsonObject("skipReasons").remove("failed");
        json.getAsJsonObject("skipReasons").addProperty("skipped", "original historical reason");
        var facts = new com.google.gson.JsonArray();
        String pastKey = com.dwinovo.numen.rdd.fact.StageKeyNormalizer.normalize("past stage");
        facts.add(pastKey);
        json.add("satisfiedStages", facts);
        TaskChain restored = TaskChain.fromJson(json.toString());
        assertHistoricalDaily(restored, pastKey);
        restored.resumeFromRecovering();
        assertTrue(restored.applyHardCodedResult("m1", true));
        restored.startCurrent();
        assertEquals("m2", restored.currentSubtask().id());
        assertHistoricalDaily(restored, pastKey);
        TaskChain twice = TaskChain.fromJson(restored.toJson());
        assertHistoricalDaily(twice, pastKey);
        assertEquals("m2", twice.currentSubtask().id());
    }

    private static void assertGoalRetained(TaskChain expected, TaskChain restored) {
        // Gson normalizes condition Numbers (1 -> 1.0); compare the complete persisted declaration semantically.
        assertEquals(com.google.gson.JsonParser.parseString(expected.toJson()).getAsJsonObject().get("goal"),
                com.google.gson.JsonParser.parseString(restored.toJson()).getAsJsonObject().get("goal"));
    }

    private static void assertHistoricalDaily(TaskChain chain, String pastKey) {
        assertEquals(SubtaskStatus.COMPLETED, chain.subtaskStatuses().get("done"));
        assertEquals(SubtaskStatus.FAILED, chain.subtaskStatuses().get("failed"));
        assertEquals(SubtaskStatus.SKIPPED, chain.subtaskStatuses().get("skipped"));
        assertNull(chain.skipReason("done"));
        assertNull(chain.skipReason("failed"));
        assertEquals("original historical reason", chain.skipReason("skipped"));
        assertEquals(java.util.Set.of(pastKey), chain.satisfiedStages());
    }

    @Test void terminalCurrentDailyRetainsItsRecordedStatusAndReasonWhileSelectorSkipsIt() {
        TaskChain original = chain(daily("daily"), main("main"));
        for (SubtaskStatus oldState : List.of(SubtaskStatus.COMPLETED, SubtaskStatus.FAILED, SubtaskStatus.SKIPPED,
                SubtaskStatus.INVALIDATED)) {
            var json = com.google.gson.JsonParser.parseString(original.toJson()).getAsJsonObject();
            json.getAsJsonObject("statuses").addProperty("daily", oldState.name());
            json.getAsJsonObject("skipReasons").remove("daily");
            if (oldState == SubtaskStatus.SKIPPED) json.getAsJsonObject("skipReasons").addProperty("daily", "original reason");
            TaskChain restored = TaskChain.fromJson(json.toString());
            restored.startCurrent();
            assertEquals("main", restored.currentSubtask().id());
            assertEquals(oldState, restored.subtaskStatuses().get("daily"));
            assertEquals(oldState == SubtaskStatus.SKIPPED ? "original reason" : null, restored.skipReason("daily"));
            restored.enterReplanningFromNegotiation("retry main work");
            restored.resumeFromReplanning();
            restored.startCurrent();
            assertEquals("main", restored.currentSubtask().id());
            assertEquals(oldState, restored.subtaskStatuses().get("daily"));
            assertEquals(oldState == SubtaskStatus.SKIPPED ? "original reason" : null, restored.skipReason("daily"));
        }
    }
}
