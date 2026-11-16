package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.BodyInstruction;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.SubtaskStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Patch extraction boundaries exercised through the real TaskChain, not a copied validator.
 * Keeps rejection priority/text, failed working-copy isolation and non-null body preservation.
 * No direct helper calls: these checks must protect both production patch entry points.
 */
class TaskChainPatchBoundaryTest {
    private static final Map<String, Object> CONDITION = Map.of("asset_key", "minecraft:stone", "minimum", 1);
    private static final BodyInstruction BODY = new BodyInstruction("mine", Map.of("block", "minecraft:stone"));

    private static TaskChain chain() {
        return new TaskChain(new Goal("g", "patch boundaries", List.of(
                new PrimaryGoal("p", "current", List.of(
                        Subtask.hardCoded("s1", "first", CONDITION),
                        Subtask.hardCoded("s2", "second", CONDITION),
                        Subtask.hardCoded("s3", "third", CONDITION, BODY),
                        Subtask.hardCoded("s4", "fourth", CONDITION))),
                new PrimaryGoal("future", "next phase", List.of(
                        Subtask.hardCoded("future-node", "later", CONDITION))))));
    }

    private static PlanPatch patch(TaskChain chain, String id, PlanPatch.Op... ops) {
        return new PlanPatch(id, "p", chain.stateVersion(), List.of(ops), "boundary check");
    }

    private static void assertRejectedUnchanged(TaskChain chain, PlanPatch patch, String code, String why) {
        String before = chain.toJson();
        PlanPatch.Receipt expected = new PlanPatch.Receipt(false, code, why, -1L);
        assertEquals(expected, chain.proposePatch(patch));
        assertEquals(before, chain.toJson(), "proposal must not publish its working list");
        assertEquals(expected, chain.commitPatch(patch, patch == null ? -1L : patch.baseStateVersion()));
        assertEquals(before, chain.toJson(), "rejected commit must not publish any partial operation");
    }

    @Test
    void ownerGuardsKeepPriorityAndExactReceipts() {
        TaskChain chain = chain();
        chain.startCurrent();
        long version = chain.stateVersion();
        assertRejectedUnchanged(chain, null, "BAD_INPUT", "patch requires an id");
        assertRejectedUnchanged(chain, new PlanPatch(" ", "other", -1L, List.of(), ""),
                "BAD_INPUT", "patch requires an id");
        assertRejectedUnchanged(chain, new PlanPatch("empty", "other", -1L, List.of(), ""),
                "BAD_INPUT", "patch has no operations");
        assertRejectedUnchanged(chain, new PlanPatch("wrong-target", "other", -1L,
                List.of(PlanPatch.Op.drop("missing")), ""),
                "NOT_CURRENT_PRIMARY", "patch targets other but current is p");
        assertRejectedUnchanged(chain, new PlanPatch("stale", "p", -1L,
                List.of(PlanPatch.Op.drop("missing")), ""),
                "STALE_VERSION", "expected stateVersion -1 but chain is at " + version);

        assertTrue(chain.commitPatch(patch(chain, "once", PlanPatch.Op.add("new", "new", CONDITION)), version).ok());
        assertRejectedUnchanged(chain, patch(chain, "once", PlanPatch.Op.drop("missing")),
                "ALREADY_COMMITTED", "patch once was already committed at version " + chain.stateVersion());
    }

    @Test
    void laterOperationFailuresDoNotPublishEarlierChangesOrInventPendingStatus() {
        TaskChain chain = chain();
        assertRejectedUnchanged(chain, patch(chain, "add-then-drop",
                        PlanPatch.Op.replace("s3", "changed", CONDITION),
                        PlanPatch.Op.add("new", "new", CONDITION), PlanPatch.Op.drop("new")),
                "NODE_NOT_PENDING", "only not-yet-started nodes may be patched: new is null");
        assertRejectedUnchanged(chain, patch(chain, "cross-primary-id",
                        PlanPatch.Op.drop("s4"), PlanPatch.Op.add("future-node", "duplicate", CONDITION)),
                "DUPLICATE_ID", "node id already used in chain: future-node");

        PlanPatch invalidSubtask = patch(chain, "constructor-failure",
                PlanPatch.Op.replace("s3", "changed", CONDITION), PlanPatch.Op.add("new", "new", Map.of()));
        String before = chain.toJson();
        IllegalArgumentException proposed = assertThrows(IllegalArgumentException.class,
                () -> chain.proposePatch(invalidSubtask));
        assertEquals("hard-coded subtask condition required", proposed.getMessage());
        assertEquals(before, chain.toJson());
        IllegalArgumentException committed = assertThrows(IllegalArgumentException.class,
                () -> chain.commitPatch(invalidSubtask, chain.stateVersion()));
        assertEquals("hard-coded subtask condition required", committed.getMessage());
        assertEquals(before, chain.toJson());
    }

    @Test
    void successfulWorkingCopyKeepsBodyAndRunningCursorAndBumpsOnlyAtCommit() {
        TaskChain chain = chain();
        chain.startCurrent();
        assertTrue(chain.applyHardCodedResult("s1", true));
        chain.startCurrent();
        Subtask running = chain.currentSubtask();
        Subtask original = chain.currentPrimary().subtasks().get(2);
        long version = chain.stateVersion();
        Map<String, Object> replacement = Map.of("asset_key", "minecraft:coal", "minimum", 4);
        PlanPatch patch = patch(chain, "success", PlanPatch.Op.replace("s3", "collect coal", replacement),
                PlanPatch.Op.drop("s4"), PlanPatch.Op.add("s5", "last", CONDITION));
        String before = chain.toJson();
        assertEquals(new PlanPatch.Receipt(true, "OK", "", version), chain.proposePatch(patch));
        assertEquals(before, chain.toJson());

        assertEquals(new PlanPatch.Receipt(true, "OK", "", version + 1), chain.commitPatch(patch, version));
        assertEquals(version + 1, chain.stateVersion());
        assertEquals(List.of("s1", "s2", "s3", "s5"),
                chain.currentPrimary().subtasks().stream().map(Subtask::id).toList());
        assertSame(running, chain.currentSubtask(), "editing future nodes must not move the running cursor");
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
        Subtask changed = chain.currentPrimary().subtasks().get(2);
        assertEquals("collect coal", changed.description());
        assertEquals(replacement, changed.condition());
        assertSame(original.body(), changed.body(), "patch must retain the original non-null body");
        assertEquals(SubtaskStatus.PENDING, chain.subtaskStatuses().get("s5"));
        assertEquals(Map.of("success", version + 1), chain.committedPatchesView());
        assertEquals("future-node", chain.goal().primaryGoals().get(1).subtasks().get(0).id());
    }
}
