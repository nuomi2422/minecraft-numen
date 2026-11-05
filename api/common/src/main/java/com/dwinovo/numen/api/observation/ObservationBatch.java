package com.dwinovo.numen.api.observation;

import java.util.List;

/**
 * Bounded composition of existing cached reads (039/B1, 2026-11-05).
 * WHY: a new batch id must never disguise two different sampling times as an atomic world snapshot.
 * No new store or refresh scheduler. Consumers choose a skew tolerance and still evaluate their predicate.
 */
public final class ObservationBatch {
    private ObservationBatch() {}
    public static final int MAX_REQUESTS = 16;
    public static final int MAX_ITEMS_PER_READ = 256;
    public static final long MAX_DEADLINE_MS = 1_000;

    public enum Consistency { SAME_TICK, BOUNDED_SKEW, NOT_COHERENT, UNKNOWN }

    public record Query(String requestId, String expectedWorldId, String expectedServerSession,
                        List<WorldObservationService.Request> requests, long maxSkewMs, long deadlineMs) {
        public Query {
            requestId = requestId == null ? "" : requestId.trim();
            expectedWorldId = expectedWorldId == null ? "" : expectedWorldId.trim();
            expectedServerSession = expectedServerSession == null ? "" : expectedServerSession.trim();
            requests = requests == null ? List.of() : List.copyOf(requests);
            if (requestId.isEmpty() || requestId.length() > 128)
                throw new IllegalArgumentException("requestId must contain 1..128 characters");
            if (requests.isEmpty() || requests.size() > MAX_REQUESTS)
                throw new IllegalArgumentException("batch needs 1.." + MAX_REQUESTS + " requests");
            if (maxSkewMs < 0 || maxSkewMs > 60_000)
                throw new IllegalArgumentException("maxSkewMs must be 0..60000");
            if (deadlineMs < 0 || deadlineMs > MAX_DEADLINE_MS)
                throw new IllegalArgumentException("deadlineMs must be 0..1000");
        }
    }

    public record Result(String requestId, WorldObservationService.Status status, Consistency consistency,
                         long observedSkewMs, List<WorldObservationService.Response> responses, String reason) {
        public Result { responses = responses == null ? List.of() : List.copyOf(responses); }
        public boolean coherent() {
            return status == WorldObservationService.Status.OK
                    && (consistency == Consistency.SAME_TICK || consistency == Consistency.BOUNDED_SKEW);
        }
        public static Result error(String id, String reason) {
            return new Result(id, WorldObservationService.Status.ERROR, Consistency.UNKNOWN, -1, List.of(), reason);
        }
    }
}
