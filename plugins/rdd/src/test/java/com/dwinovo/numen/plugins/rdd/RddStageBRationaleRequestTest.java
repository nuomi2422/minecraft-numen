package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.RationaleContext;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.core.PlanningAssetSnapshot;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 044 B1: existing, applicable reasons must reach the actual Stage-B request consumer.
 * WHY (2026-11-14): Stage-A appends freshRationale, while Stage-B sends only knowledge.
 * This is an isolated red candidate, not a replay of V16 or evidence of its root cause.
 * Use only the managed plugins:rdd test selector after the parent grants the Java slot.
 * Real RationaleContext scope/revision checks are used, not an always-fresh fake; all
 * reason text below is explicitly synthetic and never enters a natural runtime input.
 */
class RddStageBRationaleRequestTest {
    private static final String CHAIN = "fixture-stage-b-chain";
    private static final long PLAN_REVISION = 37L;
    private static final String KNOWLEDGE = "fixture-only Stage-B request\nretain original knowledge exactly";
    private final UUID companionId = UUID.randomUUID();

    @AfterEach
    void cleanup() {
        RationaleContext.clear(companionId);
    }

    @Test
    void applicableExistingReasonReachesTheRealStageBFinalContent() {
        RationaleContext.noteCurrentChain(companionId, CHAIN);
        RationaleContext.set(companionId, fixtureReason(), PLAN_REVISION, CHAIN);
        assertTrue(RationaleContext.applicable(companionId, PLAN_REVISION, CHAIN));
        String applicable = RationaleContext.renderApplicable(companionId, PLAN_REVISION, CHAIN);
        assertFalse(applicable.isBlank(), "the input is a real, scoped existing reason, not missing evidence");
        assertTrue(applicable.contains("<why>"));
        assertTrue(applicable.contains("<risks>"));
        assertTrue(applicable.contains("<source>fixture:stage-b-request-contract</source>"));
        assertTrue(applicable.contains("<conditions>"));

        String actual = RddDecomposer.stageBRequestContent(KNOWLEDGE, applicable);

        assertEquals(RddStagePlanner.withRationale(KNOWLEDGE, applicable), actual,
                "Stage-B must preserve the same applicable reason Stage-A already sends; no new reason is generated");
    }

    @Test
    void absentReasonLeavesTheOriginalRequestUnchangedWithoutInventingEvidence() {
        RationaleContext.noteCurrentChain(companionId, CHAIN);
        assertNull(RationaleContext.get(companionId));
        assertEquals("ABSENT", RationaleContext.whyStatus(RationaleContext.get(companionId)));
        String absent = RationaleContext.renderApplicable(companionId, PLAN_REVISION, CHAIN);
        assertEquals("", absent);

        String actual = RddDecomposer.stageBRequestContent(KNOWLEDGE, absent);

        assertEquals(KNOWLEDGE, actual);
        assertFalse(actual.contains("<rationale>"));
        assertFalse(actual.contains("<why>"));
        assertFalse(actual.contains("<risks>"));
    }

    @Test
    void oldRevisionAndAnotherChainRemainHistoryNotCurrentRequestEvidence() {
        RationaleContext.noteCurrentChain(companionId, CHAIN);
        RationaleContext.set(companionId, fixtureReason(), PLAN_REVISION - 1L, CHAIN);
        assertFalse(RationaleContext.peek(companionId).isBlank(), "stored history is not absence");
        assertEquals(PLAN_REVISION - 1L, RationaleContext.generation(companionId));
        String oldRevision = RationaleContext.renderApplicable(companionId, PLAN_REVISION, CHAIN);
        assertEquals("", oldRevision);
        assertEquals(KNOWLEDGE, RddDecomposer.stageBRequestContent(KNOWLEDGE, oldRevision));

        RationaleContext.set(companionId, fixtureReason(), PLAN_REVISION, CHAIN);
        String replacementChain = "fixture-stage-b-replacement-chain";
        RationaleContext.noteCurrentChain(companionId, replacementChain);
        assertFalse(RationaleContext.peek(companionId).isBlank(), "a chain replacement does not rewrite historical reason text");
        String otherChain = RationaleContext.renderApplicable(companionId, PLAN_REVISION, replacementChain);
        assertEquals("", otherChain);
        assertEquals(KNOWLEDGE, RddDecomposer.stageBRequestContent(KNOWLEDGE, otherChain));
    }

    // These execute the real Stage-B prompt/provider/dispatch consumer, not a fresh-helper mirror.
    // Runtime replacement and revision advancement happen inside the optional knowledge provider.
    @Test
    void unchangedOwnerSendsItsReasonAndOriginalPlanningInputs() {
        TaskChain owner = owner("fixture-only captured theme");
        long revision = owner.planRevision();
        declareReason(owner, fixtureReason());
        AtomicReference<TaskChain> live = new AtomicReference<>(owner);
        AtomicInteger knowledgeCalls = new AtomicInteger();
        List<String> sent = dispatch(owner, revision, owner.currentPrimary().description(), live,
                (id, theme, stage, failures) -> {
                    knowledgeCalls.incrementAndGet();
                    assertEquals(companionId, id);
                    assertEquals(owner.currentPrimary().description(), theme);
                    assertEquals("stage_b", stage);
                    assertEquals(List.of("fixture-only failed path"), failures);
                    return KNOWLEDGE;
                });
        assertEquals(1, knowledgeCalls.get());
        assertEquals(1, sent.size());
        String request = sent.getFirst();
        assertTrue(request.contains(owner.currentPrimary().description()));
        assertTrue(request.contains("fixture-only completed stage"));
        assertTrue(request.contains("fixture-only replan hint"));
        assertTrue(request.contains(KNOWLEDGE));
        assertTrue(request.endsWith(RationaleContext.renderApplicable(
                companionId, revision, owner.chainInstanceId())));
        assertTrue(request.contains("<why>"));
        assertTrue(request.contains("<risks>"));
        assertTrue(request.contains("<source>fixture:stage-b-request-contract</source>"));
        assertTrue(request.contains("<conditions>"));
    }

    @Test
    void providerReplacingRuntimeCannotPairOldThemeWithNewChainReason() {
        TaskChain owner = owner("fixture-only OLD_THEME");
        long revision = owner.planRevision();
        declareReason(owner, fixtureReason());
        TaskChain replacement = owner("fixture-only NEW_THEME");
        assertEquals(revision, replacement.planRevision(), "revision counters restart per chain");
        AtomicReference<TaskChain> live = new AtomicReference<>(owner);
        List<String> sent = dispatch(owner, revision, owner.currentPrimary().description(), live,
                (id, theme, stage, failures) -> {
                    assertSame(owner, live.get(), "replacement must happen inside the actual knowledge call");
                    live.set(replacement);
                    declareReason(replacement, replacementReason());
                    return KNOWLEDGE;
                });
        assertEquals(1, sent.size(), "optional stale reason is omitted, not a new planning BLOCK");
        assertTrue(sent.getFirst().contains("OLD_THEME"));
        assertTrue(sent.getFirst().contains(KNOWLEDGE));
        assertNoReason(sent.getFirst());
        assertTrue(RationaleContext.peek(companionId).contains("NEW_OWNER_REASON"),
                "the forbidden reason really exists in current state");
    }

    @Test
    void callerOwnerAlreadyReplacedBeforeEntryCannotBorrowCurrentReason() {
        TaskChain owner = owner("fixture-only captured OLD_THEME");
        TaskChain replacement = owner("fixture-only replacement NEW_THEME");
        declareReason(replacement, replacementReason());
        AtomicReference<TaskChain> live = new AtomicReference<>(replacement);
        List<String> sent = dispatch(owner, owner.planRevision(), owner.currentPrimary().description(), live,
                (id, theme, stage, failures) -> KNOWLEDGE);
        assertEquals(1, sent.size());
        assertTrue(sent.getFirst().contains("OLD_THEME"));
        assertNoReason(sent.getFirst());
    }

    @Test
    void providerAdvancingSameChainRevisionCannotAttachTheNewRevisionReason() {
        TaskChain owner = owner("fixture-only revision-bound theme");
        long revision = owner.planRevision();
        declareReason(owner, fixtureReason());
        AtomicReference<TaskChain> live = new AtomicReference<>(owner);
        List<String> sent = dispatch(owner, revision, owner.currentPrimary().description(), live,
                (id, theme, stage, failures) -> {
                    owner.nextPlanRevision();
                    declareReason(owner, replacementReason());
                    return KNOWLEDGE;
                });
        assertSame(owner, live.get());
        assertEquals(revision + 1L, owner.planRevision());
        assertEquals(1, sent.size());
        assertNoReason(sent.getFirst());
        assertTrue(RationaleContext.peek(companionId).contains("NEW_OWNER_REASON"));
    }

    @Test
    void storedOldRevisionIsNotSentEvenWhenRuntimeStillOwnsTheTheme() {
        TaskChain owner = owner("fixture-only current revision theme");
        declareReason(owner, fixtureReason());
        owner.nextPlanRevision();
        List<String> sent = dispatch(owner, owner.planRevision(), owner.currentPrimary().description(),
                new AtomicReference<>(owner), (id, theme, stage, failures) -> KNOWLEDGE);
        assertEquals(1, sent.size());
        assertNoReason(sent.getFirst());
        assertFalse(RationaleContext.peek(companionId).isBlank(), "history is not absence");
    }

    @Test
    void noCallerIdentityOmitsReasonInsteadOfGuessingTheLatestRuntime() {
        TaskChain current = owner("fixture-only current chain theme");
        declareReason(current, replacementReason());
        List<String> sent = dispatch(null, -1L, "fixture-only independent recovery objective",
                new AtomicReference<>(current), (id, theme, stage, failures) -> KNOWLEDGE);
        assertEquals(1, sent.size());
        assertTrue(sent.getFirst().contains("fixture-only independent recovery objective"));
        assertTrue(sent.getFirst().contains(KNOWLEDGE));
        assertNoReason(sent.getFirst());
    }

    @Test
    void missingReasonDoesNotBlockTheActualConsumer() {
        TaskChain owner = owner("fixture-only no reason theme");
        RationaleContext.noteCurrentChain(companionId, owner.chainInstanceId());
        List<String> sent = dispatch(owner, owner.planRevision(), owner.currentPrimary().description(),
                new AtomicReference<>(owner), (id, theme, stage, failures) -> KNOWLEDGE);
        assertEquals(1, sent.size());
        assertNoReason(sent.getFirst());
    }

    @Test
    void providerFailureKeepsSameOwnerReasonAndDispatchesWithoutKnowledge() {
        TaskChain owner = owner("fixture-only knowledge failure theme");
        long revision = owner.planRevision();
        declareReason(owner, fixtureReason());
        List<String> sent = dispatch(owner, revision, owner.currentPrimary().description(),
                new AtomicReference<>(owner), (id, theme, stage, failures) -> {
                    throw new IllegalStateException("fixture-only optional provider failure");
                });
        assertEquals(1, sent.size());
        assertTrue(sent.getFirst().contains("fixture-only knowledge failure theme"));
        assertFalse(sent.getFirst().contains(KNOWLEDGE));
        assertTrue(sent.getFirst().endsWith(RationaleContext.renderApplicable(
                companionId, revision, owner.chainInstanceId())));
        assertTrue(sent.getFirst().contains("<why>"));
    }

    private TaskChain owner(String theme) {
        // Deliberately identical goal/primary IDs: only chain instance and revision establish ownership.
        return new TaskChain(new Goal("fixture-goal", theme,
                List.of(PrimaryGoal.unexpanded("fixture-primary", theme, List.of()))));
    }

    private void declareReason(TaskChain owner, RationaleContext.Entry why) {
        RationaleContext.noteCurrentChain(companionId, owner.chainInstanceId());
        RationaleContext.set(companionId, why, owner.planRevision(), owner.chainInstanceId());
    }

    private List<String> dispatch(TaskChain owner, long revision, String theme,
                                  AtomicReference<TaskChain> live, RddPlanningKnowledge.Provider knowledge) {
        List<String> sent = new ArrayList<>();
        RddDecomposer.dispatchStageB(companionId, theme, owner, revision, 0,
                List.of("fixture-only completed stage"), "fixture-only replan hint",
                PlanningAssetSnapshot.from(Map.of("minecraft:bread", 3), null),
                List.of("fixture-only failed path"), knowledge, live::get, sent::add);
        return sent;
    }

    private static void assertNoReason(String request) {
        assertFalse(request.contains("<rationale>"));
        assertFalse(request.contains("<why>"));
        assertFalse(request.contains("<risks>"));
        assertFalse(request.contains("NEW_OWNER_REASON"));
        assertFalse(request.contains("fixture:stage-b-request-contract"));
    }

    private static RationaleContext.Entry replacementReason() {
        return new RationaleContext.Entry("fixture-only NEW_OWNER_REASON from replacement input",
                "fixture-only new risk", "fixture:new-owner", "fixture-only new applicability");
    }

    private static RationaleContext.Entry fixtureReason() {
        return new RationaleContext.Entry(
                "fixture-only causal reason: a prerequisite explains the requested work",
                "fixture-only uncertainty; not a measured world fact",
                "fixture:stage-b-request-contract",
                "fixture only: active chain and exactly matching plan revision");
    }
}
