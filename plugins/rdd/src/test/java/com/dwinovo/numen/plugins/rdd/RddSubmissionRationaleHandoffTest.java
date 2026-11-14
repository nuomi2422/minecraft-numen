package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.RationaleContext;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.PrimarySpec;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.PlanningAssetSnapshot;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit submitter why must survive the REAL pending/Stage-A callback, bind and first consumers.
 * WHY (2026-11-14): Stage-A rendered it, then its callback discarded it; unexpanded context and
 * the first lazy revision also lost it. Host-only IO is replaced, not the production handoff.
 * Run via managed plugins:rdd TestOnly after the parent grants a slot. Synthetic inputs only;
 * no Minecraft lifecycle, cold-start durability or live behavior is claimed by this JVM suite.
 */
class RddSubmissionRationaleHandoffTest {
    private static final String OBJECTIVE = "fixture-only causal submission objective";
    private static final String THEME = "fixture-only first theme";
    private final UUID id = UUID.randomUUID();
    private final Map<UUID, RationaleContext.Entry> pending = new HashMap<>();
    private final AtomicReference<TaskChain> live = new AtomicReference<>();
    private final RddCallbackGuard guard = new RddCallbackGuard();
    private final ArrayDeque<Runnable> server = new ArrayDeque<>();
    private final List<String> stageA = new ArrayList<>();
    private final List<RationaleContext.Entry> callbackReasons = new ArrayList<>();
    private final AtomicReference<Consumer<List<PrimarySpec>>> completion = new AtomicReference<>();

    @AfterEach
    void cleanup() { RationaleContext.clear(id); }

    @Test
    void submittedReasonReachesStageAAndTheSameAcceptedCallback() {
        RationaleContext.Entry why = submitter();
        pending.put(id, why);
        start(guard.replace(id));
        assertFalse(pending.containsKey(id), "pending reason is consumed exactly once before async completion");
        assertEquals(1, stageA.size());
        assertTrue(stageA.getFirst().endsWith(RationaleContext.renderEntry(why)));
        completeStages();
        assertEquals(1, callbackReasons.size());
        assertSame(why, callbackReasons.getFirst(), "not re-read from pending or invented at bind time");
    }

    @Test
    void acceptedSubmissionReasonReachesTheFirstActualStageBDispatch() {
        pending.put(id, submitter());
        start(guard.replace(id));
        completeStages();
        String request = firstStageB();
        assertTrue(request.contains(THEME));
        assertTrue(request.endsWith(RationaleContext.renderEntry(submitter())));
        assertTrue(request.contains("<why>"));
    }

    @Test
    void acceptedSubmissionReasonSurvivesTheUnexpandedExecutionContext() {
        pending.put(id, submitter());
        start(guard.replace(id));
        completeStages();
        TaskChain chain = live.get();
        assertTrue(chain.currentPrimary().unexpanded());
        assertNull(chain.currentSubtask());
        RddRationale.refreshContext(id, chain);
        assertEquals(RationaleContext.renderEntry(submitter()), RationaleContext.render(id));
    }

    @Test
    void acceptedBindingItselfPublishesTheExplicitReasonForItsNewChain() {
        TaskChain chain = chain(THEME);
        live.set(chain);
        RddRationale.bound(id, chain, submitter(), live::get);
        assertEquals(chain.chainInstanceId(), RationaleContext.chain(id));
        assertEquals(chain.planRevision(), RationaleContext.generation(id));
        assertEquals(RationaleContext.renderEntry(submitter()), RationaleContext.render(id));
    }

    @Test
    void unexpandedContextMustNotEraseAnAlreadyApplicableReason() {
        TaskChain chain = chain(THEME);
        live.set(chain);
        declare(chain, submitter());
        RddRationale.refreshContext(id, chain);
        assertEquals(RationaleContext.renderEntry(submitter()), RationaleContext.render(id));
    }

    @Test
    void successfulFirstLazyExpansionKeepsTheSameReasonForExecutionAtTheNewRevision() {
        TaskChain chain = chain(THEME);
        live.set(chain);
        declare(chain, submitter());
        long oldRevision = chain.planRevision();
        RddRationale.expand(id, chain, live::get, () -> expand(chain));
        assertEquals(oldRevision + 1L, chain.planRevision());
        assertNotNull(chain.currentSubtask());
        RddRationale.refreshContext(id, chain);
        assertTrue(RationaleContext.applicable(id, chain.planRevision(), chain.chainInstanceId()));
        assertEquals(RationaleContext.renderEntry(submitter()), RationaleContext.render(id));
    }

    @Test
    void failedStageAUsesTheSameExplicitReasonInTheActualFallbackContent() {
        pending.put(id, submitter());
        RddCallbackGuard.Ticket ticket = guard.replace(id);
        List<String> fallback = new ArrayList<>();
        RddRationale.planSubmission(id, pending,
                (why, done) -> done.accept(List.of()),
                (stages, why) -> guard.dispatch(ticket, server::addLast, () -> {
                    assertTrue(stages.isEmpty());
                    fallback.add(RddDecomposer.fallbackRequestContent(id, OBJECTIVE, why, snapshot(),
                            (companion, objective, stage, failures) -> "fixture-only fallback knowledge"));
                }));
        drain();
        assertEquals(1, fallback.size());
        assertTrue(fallback.getFirst().contains("fixture-only fallback knowledge"));
        assertTrue(fallback.getFirst().endsWith(RationaleContext.renderEntry(submitter())));
    }

    @Test
    void lateOldCallbackCannotBindOrReplaceTheNewSubmissionsReason() {
        pending.put(id, submitter());
        start(guard.replace(id));
        Consumer<List<PrimarySpec>> old = completion.get();
        RationaleContext.Entry newer = replacement("fixture:new-submit");
        pending.put(id, newer);
        start(guard.replace(id));
        Consumer<List<PrimarySpec>> newest = completion.get();
        newest.accept(stages());
        drain();
        TaskChain accepted = live.get();
        assertNotNull(accepted);
        old.accept(stages());
        drain();
        assertSame(accepted, live.get());
        assertEquals(1, callbackReasons.size(), "old callback is rejected by the existing ticket guard");
        assertSame(newer, callbackReasons.getFirst());
        assertEquals(RationaleContext.renderEntry(newer), RationaleContext.render(id));
    }

    @Test
    void pendingReasonWrittenAfterStageADispatchCannotContaminateTheOldAcceptedOwner() {
        RationaleContext.Entry original = submitter();
        pending.put(id, original);
        start(guard.replace(id));
        RationaleContext.Entry later = replacement("fixture:later-pending");
        pending.put(id, later);
        completeStages();
        assertSame(later, pending.get(id));
        assertSame(original, callbackReasons.getFirst());
        assertEquals(RationaleContext.renderEntry(original), RationaleContext.render(id));
    }

    @Test
    void rejectedOrFailedBindingDoesNotCreateAReasonForAnUnacceptedChain() {
        TaskChain accepted = chain("fixture-only existing chain");
        TaskChain rejected = chain("fixture-only rejected chain");
        live.set(accepted);
        declare(accepted, replacement("fixture:accepted"));
        RddRationale.bound(id, rejected, submitter(), live::get);
        assertEquals(RationaleContext.renderEntry(replacement("fixture:accepted")), RationaleContext.render(id));
        assertEquals(accepted.chainInstanceId(), RationaleContext.chain(id));
    }

    @Test
    void missingExplicitReasonRemainsAbsenceInsteadOfReusingTheOldGoalReason() {
        TaskChain old = chain("fixture-only old goal");
        declare(old, replacement("fixture:old-goal"));
        TaskChain accepted = chain(THEME);
        live.set(accepted);
        RddRationale.bound(id, accepted, null, live::get);
        RddRationale.refreshContext(id, accepted);
        assertEquals("", RationaleContext.render(id));
        assertEquals("", RationaleContext.renderApplicable(id, accepted.planRevision(), accepted.chainInstanceId()));
        assertFalse(firstStageB().contains("<rationale>"));
    }

    @Test
    void failedLazyExpansionDoesNotPromoteOldReasonToAnUnacceptedRevision() {
        TaskChain chain = chain(THEME);
        live.set(chain);
        declare(chain, submitter());
        long oldRevision = chain.planRevision();
        assertThrows(IllegalStateException.class, () -> RddRationale.expand(id, chain, live::get, () -> {
            chain.nextPlanRevision();
            throw new IllegalStateException("fixture-only rejected generated plan");
        }));
        assertEquals(oldRevision, RationaleContext.generation(id));
        assertEquals("", RationaleContext.renderApplicable(id, chain.planRevision(), chain.chainInstanceId()));
    }

    @Test
    void replacementDuringExpansionCannotReceiveTheOriginalSubmissionReason() {
        TaskChain old = chain(THEME);
        live.set(old);
        declare(old, submitter());
        TaskChain replacement = chain("fixture-only replacement theme");
        RddRationale.expand(id, old, live::get, () -> {
            expand(old);
            live.set(replacement);
            declare(replacement, replacement("fixture:new-chain"));
        });
        assertEquals(replacement.chainInstanceId(), RationaleContext.chain(id));
        assertEquals(RationaleContext.renderEntry(replacement("fixture:new-chain")), RationaleContext.render(id));
    }

    @Test
    void patchReasonReplacingTheSubmissionAtInstallIsNotOverwritten() {
        TaskChain chain = chain(THEME);
        live.set(chain);
        declare(chain, submitter());
        RationaleContext.Entry patch = replacement("planner_tool:rdd_patch:fixture");
        RddRationale.expand(id, chain, live::get, () -> {
            expand(chain);
            declare(chain, patch);
        });
        assertSame(patch, RationaleContext.get(id));
        assertEquals(RationaleContext.renderEntry(patch), RationaleContext.render(id));
    }

    @Test
    void staleOriginalReasonIsNotGuessedForwardByLazyExpansion() {
        TaskChain chain = chain(THEME);
        live.set(chain);
        declare(chain, submitter());
        chain.nextPlanRevision();
        RddRationale.expand(id, chain, live::get, () -> expand(chain));
        assertEquals("", RationaleContext.renderApplicable(id, chain.planRevision(), chain.chainInstanceId()));
    }

    @Test
    void absentReasonDoesNotBlockNormalStageAAndBinding() {
        start(guard.replace(id));
        completeStages();
        assertEquals(1, stageA.size());
        assertFalse(stageA.getFirst().contains("<rationale>"));
        assertNull(callbackReasons.getFirst());
        assertNotNull(live.get());
        assertFalse(firstStageB().contains("<rationale>"));
    }

    @Test
    void productionWiringUsesTheSameHandoffAndExecutionContextConsumer() throws Exception {
        String plugin = source("RddPlugin");
        assertTrue(plugin.contains("RddRationale.planSubmission(uuid, PENDING_PLAN_WHY"));
        assertTrue(plugin.contains("(why, done) -> RddStagePlanner.planStages(uuid, objective, why, done)"));
        assertTrue(plugin.contains("(stages, why) -> onServer(server, ticket, () -> {"));
        assertTrue(plugin.contains("RddChainFactory.fromStages(uuid, objective, stages), restartRequested, why)"));
        assertTrue(plugin.contains("RddRationale.bound(companionId, fresh, why"));
        assertTrue(plugin.contains("RddRationale.refreshContext(uuid, chain)"));
        String driver = source("RddGoalDriver");
        assertTrue(driver.contains("RddRationale.expand(companionId, chain"));
        assertTrue(driver.indexOf("RddRationale.expand(companionId, chain")
                < driver.indexOf("int rev = chain.nextPlanRevision();"));
        assertTrue(source("RddStagePlanner").contains("dispatchStages(companionId, objective, why"));
        assertTrue(source("RddDecomposer").contains("fallbackRequestContent(companionId, objective, why"));
    }

    private void start(RddCallbackGuard.Ticket ticket) {
        RddRationale.planSubmission(id, pending, (why, done) -> {
            RddStagePlanner.dispatchStages(id, OBJECTIVE, why, snapshot(), "fixture-only facts",
                    (companion, objective, stage, failures) -> "fixture-only Stage-A knowledge", () -> "", stageA::add);
            completion.set(done);
        }, (stages, why) -> guard.dispatch(ticket, server::addLast, () -> {
            TaskChain accepted = chain(stages.getFirst().description());
            live.set(accepted);
            RddRationale.bound(id, accepted, why, live::get);
            callbackReasons.add(why);
        }));
    }

    private void completeStages() { completion.get().accept(stages()); drain(); }
    private void drain() { while (!server.isEmpty()) server.removeFirst().run(); }
    private static List<PrimarySpec> stages() { return List.of(new PrimarySpec(THEME, List.of())); }
    private static PlanningAssetSnapshot snapshot() { return PlanningAssetSnapshot.from(Map.of("minecraft:bread", 3), null); }

    private String firstStageB() {
        TaskChain owner = live.get();
        List<String> sent = new ArrayList<>();
        RddDecomposer.dispatchStageB(id, owner.currentPrimary().description(), owner, owner.planRevision(), 0,
                List.of(), "", snapshot(), List.of(), (companion, objective, stage, failures) -> "", live::get, sent::add);
        assertEquals(1, sent.size());
        return sent.getFirst();
    }

    private static TaskChain chain(String theme) {
        return new TaskChain(new Goal("fixture-goal", OBJECTIVE,
                List.of(PrimaryGoal.unexpanded("fixture-primary", theme, List.of()))));
    }

    private static void expand(TaskChain chain) {
        int revision = chain.nextPlanRevision();
        chain.expandCurrentPrimary(List.of(Subtask.hardCoded("fixture-step-r" + revision,
                "fixture-only bread step", Map.of("asset_key", "minecraft:bread", "minimum", 1))));
    }

    private void declare(TaskChain chain, RationaleContext.Entry why) {
        RationaleContext.noteCurrentChain(id, chain.chainInstanceId());
        RationaleContext.set(id, why, chain.planRevision(), chain.chainInstanceId());
    }

    private static RationaleContext.Entry submitter() {
        return new RationaleContext.Entry("fixture-only causal requirement: reliable supply makes the journey possible",
                "fixture-only avoid losing the prerequisite", "fixture:explicit-submitter", "fixture-only same accepted objective");
    }

    private static RationaleContext.Entry replacement(String source) {
        return new RationaleContext.Entry("fixture-only replacement reason from the newly accepted decision",
                "fixture-only changed risks", source, "fixture-only changed applicability");
    }

    private static String source(String name) throws Exception {
        for (Path root = Path.of("").toAbsolutePath(); root != null; root = root.getParent()) {
            Path path = root.resolve("src/main/java/com/dwinovo/numen/plugins/rdd/" + name + ".java");
            if (Files.isRegularFile(path)) return Files.readString(path);
        }
        throw new AssertionError("cannot find production source for " + name);
    }
}
