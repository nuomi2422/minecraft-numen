package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.api.observation.WorldObservationService.*;
import com.dwinovo.numen.worldstate.core.freshness.TopicFreshnessPolicy;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.model.Origin;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.observation.*;
import com.dwinovo.numen.worldstate.core.observation.payload.SelfPayload;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Pure tests of the selector used by the real cache-only host read (2026-11-06). */
class SelfSampleReaderTest {
    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String DIM = "minecraft:overworld";
    private static final long NOW = 10_000;

    private static Observation sample(String world, String session, UUID id, String dim, long at, Freshness f) {
        var payload = new SelfPayload(dim, id, null, null, null, null,
                18f, 20f, 15, 2f, List.of(), null, null);
        var key = new ObservationKey(world, dim, Topic.SELF, "player:" + id, "");
        return new Observation(key, payload, "self", Origin.DIRECT, 50, at,
                com.dwinovo.numen.worldstate.core.model.Coverage.COMPLETE, f, 1, 1, session);
    }
    private static Observation current(String dim) {
        return sample("world", "session", ID, dim, NOW - 100, Freshness.CURRENT);
    }
    private static Response read(List<Observation> samples, long age) {
        return SelfSampleReader.read(samples, TopicFreshnessPolicy.defaults(), "world", "session",
                "test", ID, age, 42, NOW);
    }
    private static void unavailable(Response r) {
        assertNotEquals(Status.OK, r.status());
        assertTrue(r.items().isEmpty(), "unavailable samples must not leak present-tense state values");
    }

    @Test void uniqueSampleReturnsSampledDimensionAndExistingProjection() {
        var r = read(List.of(current("minecraft:the_nether")), 1000);
        assertEquals(Status.OK, r.status());
        assertEquals("minecraft:the_nether", r.metadata().dimension());
        assertEquals(ID.toString(), r.metadata().subjectId());
        assertEquals("world", r.metadata().worldId());
        assertEquals("session", r.metadata().serverSession());
        assertEquals(42, r.watermark());
        assertEquals(10_900, r.metadata().validUntilMs());
        assertTrue(r.items().getFirst().unknownFields().contains("main"));
        assertEquals(18, ((Number) r.items().getFirst().values().get("hp")).intValue());
    }
    @Test void isolatesUuidWorldAndSession() {
        var wrong = List.of(
                sample("other", "session", ID, DIM, NOW - 100, Freshness.CURRENT),
                sample("world", "previous", ID, DIM, NOW - 100, Freshness.CURRENT),
                sample("world", "session", UUID.randomUUID(), DIM, NOW - 100, Freshness.CURRENT));
        unavailable(read(wrong, 0));
        var mixed = new java.util.ArrayList<>(wrong);
        mixed.add(current(DIM));
        assertEquals(Status.OK, read(mixed, 0).status());
    }
    @Test void ambiguousDimensionsFailClosedRegardlessOfOrderingOrAge() {
        var first = current(DIM);
        var second = current("minecraft:the_nether");
        unavailable(read(List.of(first, second), 0));
        unavailable(read(List.of(second, first), 0));
        unavailable(read(List.of(first, sample("world", "session", ID, "minecraft:the_nether", 1, Freshness.CURRENT)), 0));
    }
    @Test void missingStaleFutureHistoricalAndUnknownFailClosed() {
        unavailable(read(List.of(), 0));
        unavailable(read(List.of(sample("world", "session", ID, DIM, NOW - 3001, Freshness.CURRENT)), 0));
        unavailable(read(List.of(current(DIM)), 99));
        unavailable(read(List.of(sample("world", "session", ID, DIM, NOW + 1, Freshness.CURRENT)), 0));
        unavailable(read(List.of(sample("world", "session", ID, DIM, NOW - 1, Freshness.HISTORICAL)), 0));
        unavailable(read(List.of(sample("world", "session", ID, DIM, NOW - 1, Freshness.UNKNOWN)), 0));
        unavailable(SelfSampleReader.read(List.of(current(DIM)), TopicFreshnessPolicy.defaults(),
                "unknown-world", "session", "test", ID, 0, 0, NOW));
    }
    @Test void callerAgeCannotWidenActualTopicTtl() {
        assertEquals(12_900, read(List.of(current(DIM)), 30_000).metadata().validUntilMs());
    }
    private static Observation changed(Observation base, ObservationKey key,
                                       com.dwinovo.numen.worldstate.core.observation.ObservationPayload payload,
                                       com.dwinovo.numen.worldstate.core.model.Coverage coverage) {
        return new Observation(key, payload, base.source(), base.origin(), base.gameTick(), base.epochMs(),
                coverage, base.freshness(), base.revision(), base.dataVersion(), base.serverSession());
    }
    @Test void exactTopicAndCallerExpiryAreUnavailable() {
        unavailable(read(List.of(sample("world", "session", ID, DIM, NOW - 3000, Freshness.CURRENT)), 0));
        unavailable(read(List.of(current(DIM)), 100));
        assertEquals(Status.OK, read(List.of(current(DIM)), 101).status());
    }
    @Test void malformedPayloadUuidDimensionAndFilterFailClosed() {
        var base = current(DIM);
        var otherUuid = sample("world", "session", UUID.randomUUID(), DIM, NOW - 100, Freshness.CURRENT);
        var otherDim = current("minecraft:the_nether");
        unavailable(read(List.of(changed(base, base.key(), otherUuid.payload(), base.coverage())), 0));
        unavailable(read(List.of(changed(base, base.key(), otherDim.payload(), base.coverage())), 0));
        var filtered = new ObservationKey("world", DIM, Topic.SELF, "player:" + ID, "unexpected");
        unavailable(read(List.of(changed(base, filtered, base.payload(), base.coverage())), 0));
        unavailable(read(List.of(changed(base, base.key(),
                new com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload(DIM, 1, false, false),
                base.coverage())), 0));
    }
    @Test void incompleteCoverageFailsButUnknownIndividualFieldsKeepKnownValues() {
        var base = current(DIM);
        for (var coverage : List.of(com.dwinovo.numen.worldstate.core.model.Coverage.PARTIAL,
                com.dwinovo.numen.worldstate.core.model.Coverage.UNLOADED)) {
            var r = read(List.of(changed(base, base.key(), base.payload(), coverage)), 0);
            unavailable(r);
            assertEquals("session", r.metadata().serverSession());
        }
        var r = read(List.of(base), 0);
        assertEquals(Status.OK, r.status());
        assertTrue(r.items().getFirst().unknownFields().contains("mainHand"));
        assertTrue(r.items().getFirst().values().containsKey("hp"), "unknown inventory must not hide known hp");
    }
    @Test void legacyApiDefaultIsExplicitlyUnavailableAndDoesNotReadOrRefresh() {
        WorldObservationService legacy = request -> { throw new AssertionError("read called"); };
        unavailable(legacy.readSelf("test", ID, 1000));
    }
}
