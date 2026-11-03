package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.dwinovo.numen.rdd.core.AdvancementVerification.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline regression for advancement's four evidence states and lookup failures.
 * Run via the rdd-core JUnit suite; no Minecraft runtime or network is needed.
 * Fake callbacks are intentional: a boolean-only stub cannot distinguish an
 * unavailable registry or progress from a confirmed absence or unfinished state.
 */
class AdvancementVerificationTest {
    private static final String ID = "minecraft:story/obtain_armor";
    private static final Object ADVANCEMENT = new Object();

    @Test
    void completedProgressMatches() {
        assertEquals(MATCHED, AdvancementVerification.evaluate(ID,
                id -> Optional.of(ADVANCEMENT), advancement -> true));
    }

    @Test
    void confirmedUnfinishedProgressDoesNotMatch() {
        assertEquals(NOT_MATCHED, AdvancementVerification.evaluate(ID,
                id -> Optional.of(ADVANCEMENT), advancement -> false));
    }

    @Test
    void successfulLookupOfMissingKeyIsInvalidAndDoesNotReadProgress() {
        assertEquals(INVALID, AdvancementVerification.evaluate(ID,
                id -> Optional.empty(), advancement -> {
                    throw new AssertionError("missing entry must not read progress");
                }));
    }

    @Test
    void confirmedMissingKeyRemainsInvalidWithoutProgressCallback() {
        assertEquals(INVALID, AdvancementVerification.<Object>evaluate(ID,
                id -> Optional.empty(), null));
    }

    @Test
    void nullRegistryCallbackIsNotAnAbsentKey() {
        assertEquals(NOT_VERIFIABLE, AdvancementVerification.<Object>evaluate(ID,
                null, advancement -> true));
    }

    @Test
    void nullRegistryResultIsNotAnAbsentKey() {
        assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate(ID,
                id -> null, advancement -> {
                    throw new AssertionError("unknown entry must not read progress");
                }));
    }

    @Test
    void registryRuntimeFailureIsNotAnAbsentKey() {
        assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate(ID, id -> {
            throw new IllegalStateException("registry unavailable");
        }, advancement -> {
            throw new AssertionError("failed lookup must not read progress");
        }));
    }

    @Test
    void registryCheckedFailureIsNotAnAbsentKey() {
        assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate(ID, id -> {
            throw new Exception("registry unavailable");
        }, advancement -> true));
    }

    @Test
    void malformedHostKeyIsNotTreatedAsRegistryMiss() {
        assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate("bad key", id -> {
            throw new IllegalArgumentException("unparseable key");
        }, advancement -> true));
    }

    @Test
    void nullProgressCallbackIsNotUnfinished() {
        assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate(ID,
                id -> Optional.of(ADVANCEMENT), null));
    }

    @Test
    void nullProgressResultIsNotUnfinished() {
        assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate(ID,
                id -> Optional.of(ADVANCEMENT), advancement -> null));
    }

    @Test
    void progressRuntimeFailureIsNotUnfinished() {
        assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate(ID,
                id -> Optional.of(ADVANCEMENT), advancement -> {
                    throw new NullPointerException("progress unavailable");
                }));
    }

    @Test
    void progressCheckedFailureIsNotUnfinished() {
        assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate(ID,
                id -> Optional.of(ADVANCEMENT), advancement -> {
                    throw new Exception("progress unavailable");
                }));
    }

    @Test
    void missingOrBlankIdIsUnknownWithoutCallingRegistry() {
        for (String id : new String[] {null, "", "  ", "\u2003"}) {
            assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate(id, key -> {
                throw new AssertionError("missing id must not reach registry");
            }, advancement -> true));
        }
    }

    @Test
    void idAndExactRegistryEntryPassUnchangedToCallbacks() {
        var seenId = new AtomicReference<String>();
        var seenEntry = new AtomicReference<Object>();
        assertEquals(MATCHED, AdvancementVerification.evaluate(ID, id -> {
            seenId.set(id);
            return Optional.of(ADVANCEMENT);
        }, advancement -> {
            seenEntry.set(advancement);
            return true;
        }));
        assertEquals(ID, seenId.get());
        assertSame(ADVANCEMENT, seenEntry.get());
    }

    @Test
    void eachCallUsesFreshEvidenceWithoutCachingOrLedger() {
        var calls = new AtomicInteger();
        AdvancementVerification.RegistryLookup<Object> registry = id -> Optional.of(ADVANCEMENT);
        AdvancementVerification.ProgressLookup<Object> progress = entry -> calls.incrementAndGet() > 1;
        assertEquals(NOT_MATCHED, AdvancementVerification.evaluate(ID, registry, progress));
        assertEquals(MATCHED, AdvancementVerification.evaluate(ID, registry, progress));
        assertEquals(2, calls.get());
    }

    @Test
    void registryInterruptionRemainsUnknownAndPreservesInterrupt() {
        try {
            assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate(ID, id -> {
                throw new InterruptedException("cancelled");
            }, advancement -> true));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void progressInterruptionRemainsUnknownAndPreservesInterrupt() {
        try {
            assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate(ID,
                    id -> Optional.of(ADVANCEMENT), advancement -> {
                        throw new InterruptedException("cancelled");
                    }));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void errorsPropagateInsteadOfBeingSilentlyClassified() {
        var fatal = new AssertionError("programming failure");
        assertSame(fatal, assertThrows(AssertionError.class, () ->
                AdvancementVerification.evaluate(ID, id -> { throw fatal; }, advancement -> true)));
    }
}
