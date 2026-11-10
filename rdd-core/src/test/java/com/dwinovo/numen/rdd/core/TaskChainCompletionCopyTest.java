package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** P1-C exact candidate regressions; source only until the assigned offline test window. */
class TaskChainCompletionCopyTest {
    private TaskChain chain() {
        return new TaskChain(new Goal("copy", "copy", List.of(new PrimaryGoal("first", "iron", List.of(
                Subtask.hardCoded("mine", "mine", Map.of("type", "item", "item", "minecraft:iron_ingot", "minimum", 1)))))));
    }
    @Test void allInstanceFieldsSurviveWithoutRestartCleaning() throws Exception {
        TaskChain original = chain();
        original.startCurrent();
        original.captureAcquireBaseline("mine", null);
        original.bindExecution("mine", "execution-stays-live");
        Field token = TaskChain.class.getDeclaredField("outstandingSafePoint");
        token.setAccessible(true);
        token.set(original, new SafePoint("copy-token", 7L, SafePoint.Purpose.PATCH_NODE, 1L, Long.MAX_VALUE));
        Field seq = TaskChain.class.getDeclaredField("safePointSeq");
        seq.setAccessible(true); seq.setLong(original, 11L);
        Field reasons = TaskChain.class.getDeclaredField("settleReasons");
        reasons.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, String> values = (Map<String, String>) reasons.get(original);
        values.put("future-diagnostic", "preserve process-only reason");
        TaskChain copy = original.copyForCompletionCommit();
        assertEquals(original.toJson(), copy.toJson());
        assertEquals(original.activeExecutionId(), copy.activeExecutionId());
        assertEquals(original.primaryStatus(), copy.primaryStatus());
        // Check even process-only fields which disk JSON intentionally omits.
        for (Field field : TaskChain.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object left = field.get(original), right = field.get(copy);
            if (left instanceof ArrayDeque<?> queue) {
                assertEquals(new ArrayList<>(queue), new ArrayList<>((ArrayDeque<?>) right), field.getName());
            } else {
                assertEquals(left, right, field.getName());
            }
            if (left instanceof Collection<?> || left instanceof Map<?, ?>) assertNotSame(left, right, field.getName());
        }
    }
    @Test void candidateMutationDoesNotChangePublishedChain() {
        TaskChain original = chain();
        original.startCurrent();
        String before = original.toJson();
        long version = original.stateVersion();
        TaskChain copy = original.copyForCompletionCommit();
        copy.settlePrimaryByFact("first", "verified", true);
        assertEquals(before, original.toJson());
        assertTrue(original.satisfiedStages().isEmpty());
        original.publishCompletionCommit(copy, version);
        assertEquals(copy.toJson(), original.toJson());
    }
    @Test void stalePublicationDoesNotOverwriteLiveChange() {
        TaskChain original = chain();
        long version = original.stateVersion();
        TaskChain copy = original.copyForCompletionCommit();
        original.startCurrent();
        String before = original.toJson();
        assertThrows(IllegalStateException.class, () -> original.publishCompletionCommit(copy, version));
        assertEquals(before, original.toJson());
    }
}
