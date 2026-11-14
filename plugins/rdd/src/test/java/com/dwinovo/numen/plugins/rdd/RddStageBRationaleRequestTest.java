package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.RationaleContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

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

    private static RationaleContext.Entry fixtureReason() {
        return new RationaleContext.Entry(
                "fixture-only causal reason: a prerequisite explains the requested work",
                "fixture-only uncertainty; not a measured world fact",
                "fixture:stage-b-request-contract",
                "fixture only: active chain and exactly matching plan revision");
    }
}
