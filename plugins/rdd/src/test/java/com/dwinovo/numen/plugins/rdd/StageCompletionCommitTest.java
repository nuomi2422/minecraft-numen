package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.fact.CompletedFactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** P1-C: proposed production completion consumer and restart path, not helper false alone.
 * Test source only: this batch must not execute tests/builds until a safe window is assigned.
 */
class StageCompletionCommitTest {
    private static TaskChain chain() {
        return new TaskChain(new Goal("p1c", "completion persistence", List.of(
                PrimaryGoal.unexpanded("first", "iron stage", List.of()),
                PrimaryGoal.unexpanded("second", "diamond stage", List.of()))));
    }

    @Test void toolFactsFailureCannotPublishAndRetryUsesSameStage(@TempDir Path dir) throws Exception {
        UUID id = UUID.randomUUID();
        TaskChain chain = chain();
        CompletedFactStore facts = new CompletedFactStore();
        Path tasks = dir.resolve("tasks"), factsDir = dir.resolve("facts");
        Files.writeString(factsDir, "not a directory");
        StageCompletionCommit commit = new StageCompletionCommit(tasks, factsDir);
        String before = chain.toJson();
        var result = commit.settle(id, chain, facts, "first", "verified world", true, 1L);
        assertFalse(result.committed());
        assertFalse(result.recordedAsCompletion());
        assertEquals(before, chain.toJson());
        assertEquals(0, facts.stageCount());
        assertTrue(commit.hasPending(id));
        assertFalse(commit.canSave(id), "periodic save must not overwrite a pending candidate");
        String projected = Files.readString(tasks.resolve(id + ".json"));
        assertFalse(commit.saveRuntime(id, chain));
        assertEquals(projected, Files.readString(tasks.resolve(id + ".json")));
        Files.delete(factsDir);
        var retried = commit.settle(id, chain, facts, "first", "verified world", true, 2L);
        assertTrue(retried.committed());
        assertEquals(1, facts.stageCount());
        assertEquals("second", chain.currentPrimary().id());
        assertFalse(commit.hasPending(id));
        assertEquals(1, RddFactStore.load(factsDir, id).stageCount());
    }

    @Test void factsFailureBlocksActualReplanConsumerUntilSameStageRetry(@TempDir Path dir) throws Exception {
        UUID id = UUID.randomUUID();
        TaskChain chain = chain();
        var facts = new CompletedFactStore();
        Path factsDir = dir.resolve("facts");
        Files.writeString(factsDir, "blocked");
        var commit = new StageCompletionCommit(dir.resolve("tasks"), factsDir);
        assertFalse(commit.settle(id, chain, facts, "first", "verified", true, 1L).committed());
        long version = chain.stateVersion();
        String before = chain.toJson();
        var future = List.of(PrimaryGoal.unexpanded("replacement", "new future", List.of()));
        assertThrows(IllegalStateException.class, () -> RddReplanChainTool.replaceFuturePrimaries(
                chain, future, () -> commit.hasPending(id)));
        assertEquals(version, chain.stateVersion());
        assertEquals(before, chain.toJson());
        assertEquals(0, facts.stageCount());
        Files.delete(factsDir);
        assertTrue(commit.retryPending(id, chain, facts).committed());
        assertEquals("second", chain.currentPrimary().id());
        assertEquals(1, facts.stageCount());
        assertFalse(commit.hasPending(id));
    }

    @Test void noPendingKeepsActualReplanConsumerBehavior() {
        TaskChain chain = chain();
        long version = chain.stateVersion();
        var future = List.of(PrimaryGoal.unexpanded("replacement", "new future", List.of()));
        assertEquals(1, RddReplanChainTool.replaceFuturePrimaries(chain, future, () -> false));
        assertEquals(version + 1, chain.stateVersion());
        assertEquals("first", chain.currentPrimary().id());
        assertEquals("replacement", chain.goal().primaryGoals().get(1).id());
    }

    @Test void taskWriteFailureLeavesPublishedMemoryUnchanged(@TempDir Path dir) throws Exception {
        UUID id = UUID.randomUUID();
        TaskChain chain = chain();
        CompletedFactStore facts = new CompletedFactStore();
        Path tasks = dir.resolve("tasks");
        Files.writeString(tasks, "not a directory");
        var commit = new StageCompletionCommit(tasks, dir.resolve("facts"));
        String before = chain.toJson();
        var result = commit.settle(id, chain, facts, "first", "verified world", true, 1L);
        assertFalse(result.committed());
        assertEquals(before, chain.toJson());
        assertEquals(0, facts.stageCount());
        assertFalse(result.recordedAsCompletion());
    }

    @Test void restartRecoversTwoFilesBeforeExposingRuntime(@TempDir Path dir) throws Exception {
        UUID id = UUID.randomUUID();
        TaskChain chain = chain();
        CompletedFactStore facts = new CompletedFactStore();
        Path tasks = dir.resolve("tasks"), factsDir = dir.resolve("facts");
        var interrupted = new StageCompletionCommit(tasks, factsDir, (point) -> {
            if (point == StageCompletionCommit.WritePoint.TASK_WRITTEN) throw new IOException("simulated interruption");
        });
        String before = chain.toJson();
        var result = interrupted.settle(id, chain, facts, "first", "verified world", true, 1L);
        assertFalse(result.committed());
        assertEquals(before, chain.toJson());
        assertEquals(0, facts.stageCount());
        assertTrue(interrupted.hasPending(id));
        var restart = new StageCompletionCommit(tasks, factsDir);
        assertTrue(restart.recover(id).committed());
        TaskChain restored = TaskChain.fromJson(Files.readString(tasks.resolve(id + ".json")));
        assertEquals("second", restored.currentPrimary().id());
        assertEquals(1, RddFactStore.load(factsDir, id).stageCount());
        assertFalse(restart.hasPending(id));
        assertTrue(restart.recover(id).committed(), "recovery with no pending work is harmless");
    }

    @Test void detectorAndOptionalConfirmationBothKeepAwaitingOnFactsFailure(@TempDir Path dir) throws Exception {
        for (String source : List.of("detector", "optional")) {
            Path base = dir.resolve(source);
            Files.createDirectories(base);
            TaskChain chain = new TaskChain(new Goal("confirm", "confirm", List.of(new PrimaryGoal("first", "iron",
                    List.of(Subtask.hardCoded("step", "step", Map.of("type", "item", "item", "minecraft:iron_ingot", "minimum", 1)))))));
            chain.startCurrent();
            assertTrue(chain.applyHardCodedResult("step", true));
            assertEquals(PrimaryGoalStatus.AWAITING_SUPERVISOR, chain.primaryStatus());
            String before = chain.toJson();
            Path factsDir = base.resolve("facts");
            Files.writeString(factsDir, "blocked");
            var facts = new CompletedFactStore();
            var commit = new StageCompletionCommit(base.resolve("tasks"), factsDir);
            var result = commit.confirm(UUID.randomUUID(), chain, facts, source, 1L);
            assertFalse(result.committed());
            assertFalse(result.recordedAsCompletion());
            assertEquals(before, chain.toJson());
            assertEquals(0, facts.stageCount());
        }
    }

    @Test void finalTaskProjectionFailureIsPendingAndRetryable(@TempDir Path dir) throws Exception {
        UUID id = UUID.randomUUID();
        Path tasks = dir.resolve("tasks");
        Path blockedTarget = tasks.resolve(id + ".json").toAbsolutePath().normalize();
        Files.createDirectories(blockedTarget);
        Path sentinel = blockedTarget.resolve("task-projection-blocker.txt");
        Files.writeString(sentinel, "must remain unchanged until explicit fixture repair");
        TaskChain chain = chain();
        String before = chain.toJson();
        var facts = new CompletedFactStore();
        var commit = new StageCompletionCommit(tasks, dir.resolve("facts"));
        assertFalse(commit.settle(id, chain, facts, "first", "verified", true, 1L).committed());
        assertTrue(commit.hasPending(id));
        assertEquals(before, chain.toJson());
        assertEquals(0, facts.stageCount());
        assertEquals("must remain unchanged until explicit fixture repair", Files.readString(sentinel));
        assertFalse(Files.exists(dir.resolve("facts").resolve(id + ".json")), "TASK projection fails before FACTS projection");
        assertEquals(dir.toAbsolutePath().normalize().resolve("tasks").resolve(id + ".json"), blockedTarget);
        assertEquals(blockedTarget, sentinel.getParent(), "delete only the explicit temporary fixture child");
        Files.delete(sentinel);
        Files.delete(blockedTarget);
        assertTrue(commit.retryPending(id, chain, facts).committed());
        assertEquals("second", chain.currentPrimary().id());
    }

    @Test void waiverDoesNotBecomeCrossChainCompletion(@TempDir Path dir) {
        UUID id = UUID.randomUUID();
        TaskChain chain = chain();
        CompletedFactStore facts = new CompletedFactStore();
        var commit = new StageCompletionCommit(dir.resolve("tasks"), dir.resolve("facts"));
        var result = commit.settle(id, chain, facts, "first", "not needed", false, 1L);
        assertTrue(result.committed());
        assertFalse(result.recordedAsCompletion());
        assertEquals(0, facts.stageCount());
        assertTrue(chain.satisfiedStages().isEmpty());
    }

    @Test void failedRestartRecoveryDoesNotReportCommitted(@TempDir Path dir) throws Exception {
        UUID id = UUID.randomUUID();
        Path factsDir = dir.resolve("facts");
        Files.writeString(factsDir, "blocked");
        var commit = new StageCompletionCommit(dir.resolve("tasks"), factsDir);
        assertFalse(commit.settle(id, chain(), new CompletedFactStore(), "first", "verified", true, 1L).committed());
        var restart = new StageCompletionCommit(dir.resolve("tasks"), factsDir);
        assertFalse(restart.recover(id).committed());
        assertTrue(restart.hasPending(id));
    }


    @Test void frozenPendingReasonSurvivesEveryWriteInterruptionWithoutBorrowingLiveMemory(@TempDir Path dir) throws Exception {
        for (StageCompletionCommit.WritePoint failAt : StageCompletionCommit.WritePoint.values()) {
            UUID id = UUID.randomUUID(); Path base = dir.resolve(failAt.name());
            Path tasks = base.resolve("tasks"); TaskChain live = chain();
            var why = new com.dwinovo.numen.api.RationaleContext.Entry("accepted causal reason " + failAt,
                    "accepted risk", "fixture:accepted", "same accepted chain and revision");
            var later = new com.dwinovo.numen.api.RationaleContext.Entry("LATER_REASON_MUST_NOT_REPLACE_FROZEN",
                    "later risk", "fixture:later", "later condition");
            RddRationale.bound(id, live, why, () -> live);
            try {
                var writer = new StageCompletionCommit(tasks, base.resolve("facts"), point -> {
                    if (point == failAt) throw new IOException("controlled interruption at " + point);
                });
                assertTrue(writer.saveRuntime(id, live));
                String savedBefore = Files.readString(tasks.resolve(id + ".json"));
                String chainBefore = live.toJson(); var facts = new CompletedFactStore();
                assertFalse(writer.settle(id, live, facts, "first", "verified", true, 1L).committed());
                assertEquals(chainBefore, live.toJson());
                assertEquals(why, com.dwinovo.numen.api.RationaleContext.get(id), "IO failure cannot publish a replacement reason");
                Path pending = tasks.resolve(id + ".completion-pending");
                String frozenIntent = Files.readString(pending);
                String frozenChain = com.google.gson.JsonParser.parseString(frozenIntent).getAsJsonObject().get("chainJson").getAsString();
                String projectedBefore = Files.readString(tasks.resolve(id + ".json"));
                RddRationale.bound(id, live, later, () -> live);
                assertFalse(writer.saveRuntime(id, live), "normal saves cannot overwrite an outstanding completion intent");
                assertEquals(projectedBefore, Files.readString(tasks.resolve(id + ".json")));
                assertEquals(frozenIntent, Files.readString(pending));
                com.dwinovo.numen.api.RationaleContext.clear(id); // Cold process: pending recovery cannot read live why.
                var restarted = new StageCompletionCommit(tasks, base.resolve("facts"));
                assertTrue(restarted.recover(id).committed());
                String replayed = Files.readString(tasks.resolve(id + ".json"));
                assertEquals(frozenChain, replayed, "project/recover must write the exact frozen task payload");
                assertFalse(replayed.contains(later.why()));
                TaskChain restored = TaskChain.fromJson(replayed);
                assertTrue(RddRationale.restore(id, restored, replayed, () -> restored));
                assertEquals(why, com.dwinovo.numen.api.RationaleContext.get(id));
                assertEquals("second", restored.currentPrimary().id());
                assertFalse(restarted.hasPending(id));
                if (failAt == StageCompletionCommit.WritePoint.INTENT_WRITTEN)
                    assertEquals(savedBefore, projectedBefore, "intent write failure leaves the original task projection intact");
            } finally { com.dwinovo.numen.api.RationaleContext.clear(id); }
        }
    }

    @Test void optionalBadScopeAndLegacyPayloadCannotCorruptRestoredChain(@TempDir Path dir) throws Exception {
        UUID id = UUID.randomUUID(); TaskChain accepted = chain();
        var why = new com.dwinovo.numen.api.RationaleContext.Entry("accepted restart reason", "risk", "fixture:source", "scope");
        RddRationale.bound(id, accepted, why, () -> accepted);
        try {
            Path tasks = dir.resolve("tasks");
            var writer = new StageCompletionCommit(tasks, dir.resolve("facts"));
            assertTrue(writer.saveRuntime(id, accepted));
            String encoded = Files.readString(tasks.resolve(id + ".json"));
            var original = com.google.gson.JsonParser.parseString(encoded).getAsJsonObject();
            assertTrue(original.has("rationale"));
            for (String fault : List.of("missing", "malformed", "owner", "chain", "revision", "field", "uninstalled")) {
                var changed = original.deepCopy();
                if (fault.equals("missing")) changed.remove("rationale");
                else if (fault.equals("malformed")) changed.addProperty("rationale", "invalid optional metadata");
                else if (fault.equals("owner")) changed.getAsJsonObject("rationale").addProperty("companionId", UUID.randomUUID().toString());
                else if (fault.equals("chain")) changed.getAsJsonObject("rationale").addProperty("chainInstanceId", "other-chain");
                else if (fault.equals("revision")) changed.getAsJsonObject("rationale").addProperty("planRevision", accepted.planRevision() + 1);
                else if (fault.equals("field")) changed.getAsJsonObject("rationale").addProperty("why", 7);
                com.dwinovo.numen.api.RationaleContext.clear(id);
                TaskChain restored = TaskChain.fromJson(changed.toString());
                assertFalse(RddRationale.restore(id, restored, changed.toString(), () -> fault.equals("uninstalled") ? null : restored), fault);
                RddRationale.refreshContext(id, restored);
                assertEquals("", com.dwinovo.numen.api.RationaleContext.render(id), fault);
                assertEquals(accepted.chainInstanceId(), restored.chainInstanceId());
                assertEquals(accepted.planRevision(), restored.planRevision());
                assertEquals(accepted.goal().id(), restored.goal().id());
            }
            com.dwinovo.numen.api.RationaleContext.clear(id);
            TaskChain loaded = TaskChain.fromJson(encoded);
            assertTrue(RddRationale.restore(id, loaded, encoded, () -> loaded));
            assertTrue(writer.saveRuntime(id, loaded));
            assertEquals(why, com.dwinovo.numen.api.RationaleContext.get(id));
            assertTrue(Files.readString(tasks.resolve(id + ".json")).contains(why.why()), "later ordinary saves retain the restored same-file reason");
            // No optional metadata keeps the exact legacy serializer output and old pending replay.
            com.dwinovo.numen.api.RationaleContext.clear(id);
            assertTrue(writer.saveRuntime(id, accepted));
            assertEquals(accepted.toJson(), Files.readString(tasks.resolve(id + ".json")));
            var interrupted = new StageCompletionCommit(tasks, dir.resolve("facts"), point -> {
                if (point == StageCompletionCommit.WritePoint.INTENT_WRITTEN) throw new IOException("legacy interruption");
            });
            assertFalse(interrupted.settle(id, accepted, new CompletedFactStore(), "first", "verified", true, 1L).committed());
            assertTrue(new StageCompletionCommit(tasks, dir.resolve("facts")).recover(id).committed());
            TaskChain legacy = TaskChain.fromJson(Files.readString(tasks.resolve(id + ".json")));
            assertEquals("second", legacy.currentPrimary().id());
        } finally { com.dwinovo.numen.api.RationaleContext.clear(id); }
    }

    @Test void staleStageCannotReplacePendingCommit(@TempDir Path dir) throws Exception {
        UUID id = UUID.randomUUID();
        TaskChain chain = chain();
        Path blocked = dir.resolve("facts");
        Files.writeString(blocked, "not a directory");
        var commit = new StageCompletionCommit(dir.resolve("tasks"), blocked);
        var facts = new CompletedFactStore();
        assertFalse(commit.settle(id, chain, facts, "first", "verified", true, 1L).committed());
        assertFalse(commit.settle(id, chain, facts, "second", "different request", true, 2L).committed());
        assertEquals(0, facts.stageCount());
        assertEquals("first", chain.currentPrimary().id());
    }
}
