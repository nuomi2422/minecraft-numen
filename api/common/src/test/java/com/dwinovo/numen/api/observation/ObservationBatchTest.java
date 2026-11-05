package com.dwinovo.numen.api.observation;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static com.dwinovo.numen.api.observation.WorldObservationService.*;
import static com.dwinovo.numen.api.observation.ObservationBatch.*;

/** 039/B1 regression examples: old/partial/foreign samples cannot become a coherent batch (2026-11-05). */
class ObservationBatchTest {
    private static Request request() { return new Request("test", "overworld", "environment", Map.of(), 1000, 10, 50); }
    private static Response response(String world, String session, long at, long tick) {
        return new Response(Status.OK, "environment", "s-" + at, tick, Coverage.FULL, null, List.of(Item.of(Map.of("day_time", 1, "weather", "clear", "dimension", "overworld"))), 2,
                new ObservationMetadata(world, session, "overworld", "", "test", "key", 1, at,
                        ObservationMetadata.Freshness.CURRENT, "SERVER_LOADED", at + 1000));
    }
    private static Query query(long skew, int n) {
        return new Query("q", "world", "session", java.util.Collections.nCopies(n, request()), skew, 50);
    }
    private static Result read(Query q, Response... responses) {
        AtomicInteger index = new AtomicInteger();
        return ObservationBatchReader.read(r -> responses[index.getAndIncrement()], q, () -> 2000, () -> 0);
    }
    @Test void batchReadsOnlyAndKeepsKnownSamples() {
        AtomicInteger calls = new AtomicInteger();
        WorldObservationService svc = new WorldObservationService() {
            public Response read(Request r) { calls.incrementAndGet(); return response("world", "session", 1500, 10); }
            public Response refresh(Request r) { fail("batch must not scan"); return null; }
        };
        var r = ObservationBatchReader.read(svc, query(0, 2), () -> 2000, () -> 0);
        assertTrue(r.coherent()); assertEquals(2, calls.get()); assertEquals(Consistency.SAME_TICK, r.consistency());
        assertThrows(UnsupportedOperationException.class, () -> r.responses().clear());
    }
    @Test void boundedSkewIsNotAtomicAndExcessIsRejected() {
        var a = response("world", "session", 1500, 10);
        var b = response("world", "session", 1550, 11);
        assertEquals(Consistency.BOUNDED_SKEW, read(query(50, 2), a, b).consistency());
        assertEquals(Consistency.NOT_COHERENT, read(query(49, 2), a, b).consistency());
    }
    @Test void identicalTicksDoNotExcuseDifferentSessionsOrWorlds() {
        assertFalse(read(query(100, 1), response("other-world", "session", 1500, 10)).coherent());
        assertFalse(read(query(100, 1), response("world", "other-session", 1500, 10)).coherent());
    }
    @Test void callerAgeAndFutureClockAreCheckedEvenForEmptyLists() {
        assertFalse(read(query(100, 1), response("world", "session", 999, 10)).coherent());
        assertFalse(read(query(100, 1), response("world", "session", 2001, 10)).coherent());
    }
    @Test void legacyMetadataCannotMasqueradeAsIdentified() {
        var old = Response.ok("environment", "s", 10, Coverage.FULL, List.of());
        assertFalse(read(query(100, 1), old).coherent());
        assertEquals(ObservationMetadata.Freshness.UNKNOWN, old.metadata().freshness());
    }
    @Test void historicalEvenWithinAgeIsNotCurrent() {
        var a = response("world", "session", 1500, 10);
        var h = new Response(a.status(), a.topic(), a.snapshotId(), a.observedTick(), a.coverage(), a.reason(),
                a.items(), a.watermark(), a.metadata().withFreshness(ObservationMetadata.Freshness.HISTORICAL));
        assertFalse(read(query(100, 1), h).coherent());
    }
    @Test void unknownFieldsAndPartialCoverageDoNotPass() {
        var a = response("world", "session", 1500, 10);
        var p = new Response(Status.PARTIAL, a.topic(), "s", 10, Coverage.PARTIAL, "partial",
                List.of(new Item(Map.of("x", 1), java.util.Set.of("phase"), java.util.Set.of())), 0, a.metadata());
        assertFalse(read(query(100, 1), p).coherent());
    }
    @Test void nullAndThrowingProvidersProduceExplicitFailure() {
        assertFalse(ObservationBatchReader.read(r -> null, query(0, 1), () -> 2000, () -> 0).coherent());
        var r = ObservationBatchReader.read(q -> { throw new IllegalArgumentException("bad"); }, query(0, 1), () -> 2000, () -> 0);
        assertFalse(r.coherent()); assertEquals(Status.ERROR, r.responses().getFirst().status());
    }
    @Test void deadlineStopsBeforeAnotherReadWithoutPretendingComplete() {
        AtomicLong clock = new AtomicLong();
        AtomicInteger calls = new AtomicInteger();
        var result = ObservationBatchReader.read(q -> {
            calls.incrementAndGet(); clock.set(60_000_000); return response("world", "session", 1500, 10);
        }, query(0, 2), () -> 2000, clock::get);
        assertFalse(result.coherent()); assertEquals(1, calls.get()); assertTrue(result.reason().contains("deadline"));
    }
    @Test void firstSampleCannotAgeOutDuringLaterReads() {
        AtomicLong now = new AtomicLong(1500);
        AtomicInteger count = new AtomicInteger();
        Request r = new Request("test", "overworld", "environment", Map.of(), 100, 10, 500);
        Query q = new Query("q", "world", "session", List.of(r, r), 100, 500);
        var result = ObservationBatchReader.read(ignored -> {
            if (count.getAndIncrement() == 0) return response("world", "session", 1450, 10);
            now.set(1600); return response("world", "session", 1550, 11);
        }, q, now::get, () -> 0);
        assertFalse(result.coherent()); assertTrue(result.reason().contains("expired"));
    }
    @Test void emptyEnvironmentIsNotKnownButFreshFullEmptyContainerIs() {
        var base = response("world", "session", 1500, 10);
        var emptyEnv = new Response(Status.OK, "environment", "s", 10, Coverage.FULL, null, List.of(), 0, base.metadata());
        assertFalse(read(query(0, 1), emptyEnv).coherent());
        var req = new Request("test", "overworld", "containers", Map.of("container", "block:0,64,0"), 1000, 10, 50);
        var q = new Query("q", "world", "session", List.of(req), 0, 50);
        var emptyBox = new Response(Status.OK, "containers", "s", 10, Coverage.FULL, null, List.of(), 0, base.metadata());
        assertTrue(read(q, emptyBox).coherent());
    }
    @Test void hostExpiryIsCheckedAtDeliveryEvenWhenCallerAgeIsUnlimited() {
        var sample = response("world", "session", 1000, 10);
        var m = sample.metadata();
        var expiring = new Response(sample.status(), sample.topic(), sample.snapshotId(), sample.observedTick(),
                sample.coverage(), null, sample.items(), 0, new ObservationMetadata(m.worldId(), m.serverSession(),
                m.dimension(), m.subjectId(), m.source(), m.observationKey(), m.revision(), m.observedAtMs(),
                m.freshness(), m.visibility(), 1100));
        for (long requestedAge : new long[]{0, 10_000}) {
            var request = new Request("test", "overworld", "environment", Map.of(), requestedAge, 10, 50);
            var q = new Query("q", "world", "session", List.of(request), 100, 50);
            assertTrue(ObservationBatchReader.read(r -> expiring, q, () -> 1100, () -> 0).coherent());
            assertFalse(ObservationBatchReader.read(r -> expiring, q, () -> 1101, () -> 0).coherent());
        }
    }
    @Test void wrongSubjectAndNegativeTickAreNotCoherent() {
        assertFalse(read(query(100, 1), response("world", "session", 1500, -1)).coherent());
        var req = new Request("test", "overworld", "containers", Map.of("container", "player:self"), 1000, 10, 50);
        var q = new Query("q", "world", "session", List.of(req), 0, 50);
        var wrong = new Response(Status.OK, "containers", "s", 10, Coverage.FULL, null, List.of(), 0,
                new ObservationMetadata("world", "session", "overworld", "other", "test", "key", 1, 1500,
                        ObservationMetadata.Freshness.CURRENT, "SERVER_LOADED", 2500));
        assertFalse(read(q, wrong).coherent());
    }
    @Test void badBatchLimitsAreRejectedBeforeReading() {
        assertThrows(IllegalArgumentException.class, () -> query(0, 0));
        assertThrows(IllegalArgumentException.class, () -> query(0, 17));
        assertThrows(IllegalArgumentException.class, () -> query(-1, 1));
    }
}
