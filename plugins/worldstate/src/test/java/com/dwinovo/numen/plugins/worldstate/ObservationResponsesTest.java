package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.ObservationMetadata;
import com.dwinovo.numen.api.observation.WorldObservationService.*;
import com.dwinovo.numen.worldstate.core.model.*;
import com.dwinovo.numen.worldstate.core.observation.*;
import com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload;
import com.dwinovo.numen.worldstate.core.query.ReadResult;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the actual response mapper, especially stale empty containers and strict caller age (2026-11-05). */
class ObservationResponsesTest {
    private static final String DIM = "minecraft:overworld";
    private static ReadResult sample(long at, Freshness freshness) {
        var key = new ObservationKey("world", DIM, Topic.ENVIRONMENT, "", "");
        var o = new Observation(key, new EnvironmentPayload(DIM, 1, false, false), "env", Origin.DIRECT,
                10, at, com.dwinovo.numen.worldstate.core.model.Coverage.COMPLETE, Freshness.CURRENT, 3, 1, "session");
        return ReadResult.present(o, freshness, 0, o.coverage(), "sample");
    }
    private static Response mapped(ReadResult rr, Request request, List<Item> items, long watermark, long now) {
        return ObservationResponses.map(rr, request, items, watermark, now,
                new ObservationKey("world", DIM, Topic.ENVIRONMENT, "", ""), 30_000);
    }
    private static Request request(long age, int limit) { return new Request("test", DIM, "environment", Map.of(), age, limit, 50); }
    @Test void provenanceContainsWorldSessionSourceRevisionAndTime() {
        var r = mapped(sample(1000, Freshness.CURRENT), request(1000, 10), List.of(), 42, 1100);
        assertEquals(Status.OK, r.status()); assertTrue(r.metadata().identified());
        assertEquals("session", r.metadata().serverSession()); assertEquals(42, r.watermark());
        assertEquals(3, r.metadata().revision()); assertTrue(r.snapshotId().startsWith("session:"));
    }
    @Test void callerAgeStricterThanTopicTtlMarksAllFields() {
        var r = mapped(sample(1000, Freshness.CURRENT), request(99, 10), List.of(Item.of(Map.of("x", 1))), 0, 1100);
        assertEquals(Status.PARTIAL, r.status()); assertTrue(r.items().getFirst().staleFields().contains("x"));
        assertEquals(ObservationMetadata.Freshness.STALE, r.metadata().freshness());
    }
    @Test void staleEmptyListStillHasStaleEnvelope() {
        var r = mapped(sample(1000, Freshness.STALE), request(0, 10), List.of(), 0, 1100);
        assertEquals(Status.PARTIAL, r.status()); assertEquals(ObservationMetadata.Freshness.STALE, r.metadata().freshness());
    }
    @Test void limitCannotPreserveFullCoverage() {
        var r = mapped(sample(1000, Freshness.CURRENT), request(0, 1),
                List.of(Item.of(Map.of("x", 1)), Item.of(Map.of("x", 2))), 0, 1100);
        assertEquals(1, r.items().size()); assertEquals(com.dwinovo.numen.api.observation.WorldObservationService.Coverage.PARTIAL, r.coverage()); assertEquals(Status.PARTIAL, r.status());
    }
    @Test void futureTimestampDoesNotLookFresh() {
        var r = mapped(sample(1200, Freshness.CURRENT), request(0, 1), List.of(), 0, 1100);
        assertEquals(Status.PARTIAL, r.status()); assertEquals(ObservationMetadata.Freshness.UNKNOWN, r.metadata().freshness());
    }
    @Test void expiryUsesActualTopicPolicyAndStricterRequest() {
        var rr = sample(1000, Freshness.CURRENT);
        var noLimit = ObservationResponses.map(rr, request(0, 1), List.of(), 0, 1050, rr.key(), 100);
        var looser = ObservationResponses.map(rr, request(1000, 1), List.of(), 0, 1050, rr.key(), 100);
        var strict = ObservationResponses.map(rr, request(60, 1), List.of(), 0, 1050, rr.key(), 100);
        assertEquals(1100, noLimit.metadata().validUntilMs());
        assertEquals(1100, looser.metadata().validUntilMs());
        assertEquals(1060, strict.metadata().validUntilMs());
    }
    @Test void wrongTargetOrFilterIsRejectedAtRealMapper() {
        var rr = sample(1000, Freshness.CURRENT);
        for (var wrong : List.of(new ObservationKey("world", DIM, Topic.ENVIRONMENT, "another", ""),
                new ObservationKey("world", DIM, Topic.ENVIRONMENT, "", "another-filter")))
            assertEquals(Status.ERROR, ObservationResponses.map(rr, request(0, 1), List.of(), 0, 1050, wrong, 100).status());
    }
    @Test void missingDoesNotLookEmpty() {
        var r = mapped(null, request(0, 1), List.of(), 0, 1100);
        assertEquals(Status.PENDING, r.status()); assertFalse(r.metadata().identified());
    }
    @Test void validUntilUsesTopicTtlUnlessCallerAgeIsStricter() {
        var zero = mapped(sample(1000, Freshness.CURRENT), request(0, 10), List.of(), 0, 1100);
        assertEquals(30_000L, zero.metadata().validUntilMs() - zero.metadata().observedAtMs());
        var tight = mapped(sample(1000, Freshness.CURRENT), request(1000, 10), List.of(), 0, 1100);
        assertEquals(1_000L, tight.metadata().validUntilMs() - tight.metadata().observedAtMs());
        var loose = mapped(sample(1000, Freshness.CURRENT), request(60_000, 10), List.of(), 0, 1100);
        assertEquals(30_000L, loose.metadata().validUntilMs() - loose.metadata().observedAtMs(), "caller age must not widen topic ttl");
    }

    @Test void selfTtlCapsValidUntilAtThreeSeconds() {
        var rr = sample(1000, Freshness.CURRENT);
        var request = request(0, 10);
        var r = ObservationResponses.map(rr, request, List.of(), 0, 1100,
                new ObservationKey("world", DIM, Topic.SELF, "player:00000000-0000-0000-0000-000000000000", ""), 3_000);
        assertEquals(3_000L, r.metadata().validUntilMs() - r.metadata().observedAtMs(), "SELF topic ttl must cap validUntil at 3s");
    }

}
