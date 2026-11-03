package com.dwinovo.numen.rdd.core;

import java.util.Optional;

/**
 * Pure-JVM advancement evidence classification; the host supplies both lookups.
 * A boolean collapses registry failure, absent keys, and unfinished progress into one
 * answer, so it cannot safely distinguish an invalid condition from unknown evidence.
 * Use {@link #evaluate(String, RegistryLookup, ProgressLookup)} with callbacks for the
 * host's registry and the same player's progress. Only a successful registry lookup
 * that explicitly returns {@link Optional#empty()} proves that the key is absent.
 * This class performs no IO or storage and does not wire itself into the game host.
 */
public final class AdvancementVerification {
    private AdvancementVerification() {}

    /** Outcome of verifying one advancement for one subject. */
    public enum Result {
        MATCHED,
        NOT_MATCHED,
        INVALID,
        NOT_VERIFIABLE
    }

    /**
     * Find the advancement in an available, authoritative registry.
     * Return empty only when lookup succeeded and the key is definitely absent.
     * An unavailable registry, invalid/unparseable key or uncertain lookup must
     * instead throw or return null; neither may be represented by empty.
     */
    @FunctionalInterface
    public interface RegistryLookup<T> {
        Optional<T> find(String id) throws Exception;
    }

    /**
     * Read progress for the found advancement and intended subject. Null or an
     * exception means unavailable/unknown, not an unfinished advancement.
     */
    @FunctionalInterface
    public interface ProgressLookup<T> {
        Boolean isDone(T advancement) throws Exception;
    }

    /**
     * Classify a lookup without assuming absent data means an absent key or false
     * progress. The original ID is passed through unchanged; the host parses it.
     * Missing/blank IDs, null callbacks/results, and lookup exceptions are unknown.
     * Progress is never requested for a missing or unknown registry entry. A known
     * missing entry remains INVALID even when the progress callback is unavailable.
     * Interrupted lookups preserve the calling thread's interrupt status. JVM errors
     * are not swallowed. The callbacks are responsible for any host-side effects.
     */
    public static <T> Result evaluate(String id, RegistryLookup<T> registry,
                                      ProgressLookup<T> progress) {
        if (id == null || id.isBlank() || registry == null) return Result.NOT_VERIFIABLE;
        try {
            Optional<T> found = registry.find(id);
            if (found == null) return Result.NOT_VERIFIABLE;
            if (found.isEmpty()) return Result.INVALID;
            if (progress == null) return Result.NOT_VERIFIABLE;
            Boolean done = progress.isDone(found.get());
            if (done == null) return Result.NOT_VERIFIABLE;
            return done ? Result.MATCHED : Result.NOT_MATCHED;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return Result.NOT_VERIFIABLE;
        } catch (Exception ex) {
            return Result.NOT_VERIFIABLE;
        }
    }
}
