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
        Files.createDirectories(tasks.resolve(id + ".json"));
        TaskChain chain = chain();
        String before = chain.toJson();
        var facts = new CompletedFactStore();
        var commit = new StageCompletionCommit(tasks, dir.resolve("facts"));
        assertFalse(commit.settle(id, chain, facts, "first", "verified", true, 1L).committed());
        assertTrue(commit.hasPending(id));
        assertEquals(before, chain.toJson());
        assertEquals(0, facts.stageCount());
        Files.delete(tasks.resolve(id + ".json"));
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
