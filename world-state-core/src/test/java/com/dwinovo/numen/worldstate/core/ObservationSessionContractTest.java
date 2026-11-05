package com.dwinovo.numen.worldstate.core;

import com.dwinovo.numen.worldstate.core.freshness.TopicFreshnessPolicy;
import com.dwinovo.numen.worldstate.core.json.ObservationJson;
import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Origin;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload;
import com.dwinovo.numen.worldstate.core.provider.CollectionContext;
import com.dwinovo.numen.worldstate.core.provider.ProviderRegistry;
import com.dwinovo.numen.worldstate.core.query.QueryService;
import com.dwinovo.numen.worldstate.core.query.ReadResult;
import com.dwinovo.numen.worldstate.core.refresh.RefreshService;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import com.dwinovo.numen.worldstate.core.store.InMemoryObservationStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-05：离线锁住会话证据从采集、落盘到严格读取的契约。
 * 案例：同一进程重开同一存档，上一轮快照仍在有效期内，不能冒充新会话的当前事实。
 * 不用只测时间戳的简单方案：它漏掉未知会话、旧文件和磁盘载入后的历史语义。
 * 运行 :world-state-core:test --tests '*ObservationSessionContractTest'；不依赖 Minecraft。
 */
class ObservationSessionContractTest {
    private static final String WORLD = "live";
    private static final ObservationRequest REQUEST = ObservationRequest.entities(
            "minecraft:overworld", Bounds.at(0, 64, 0), "minecraft:sheep", null);
    private static final ObservationRequest ENVIRONMENT = ObservationRequest.environment("minecraft:overworld");
    private InMemoryObservationStore store;
    private QueryService query;
    private TestSupport.FakeEntitiesProvider provider;
    private long now;

    @BeforeEach
    void setUp() {
        now = 1_000L;
        store = new InMemoryObservationStore();
        provider = new TestSupport.FakeEntitiesProvider();
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(provider);
        query = new QueryService(store, TopicFreshnessPolicy.defaults(),
                new RefreshService(registry, store, Runnable::run), () -> now);
    }

    private CollectionContext context(String session) {
        return new CollectionContext() {
            @Override public String worldId() { return WORLD; }
            @Override public long gameTick() { return 42L; }
            @Override public long nowMs() { return now; }
            @Override public String serverSession() { return session; }
        };
    }

    private static Observation observation(String session) {
        return new Observation(ENVIRONMENT.keyFor(WORLD),
                new EnvironmentPayload("minecraft:overworld", 6000L, true, false),
                "environment", Origin.DIRECT, 42L, 1_000L, Coverage.COMPLETE,
                Freshness.CURRENT, 7L, 1, session);
    }

    @Test
    void legacyTenArgumentConstructorLeavesSessionUnknown() {
        Observation known = observation("session-a");
        Observation legacy = new Observation(known.key(), known.payload(), known.source(), known.origin(),
                known.gameTick(), known.epochMs(), known.coverage(), known.freshness(),
                known.revision(), known.dataVersion());
        assertEquals("", legacy.serverSession());
        assertEquals(known.key(), legacy.key());
        assertEquals(known.revision(), legacy.revision());
    }

    @Test
    void nullAndBlankSessionsNormalizeToExplicitUnknown() {
        for (String session : new String[]{null, "", " ", "\t"}) {
            assertEquals("", observation(session).serverSession());
        }
    }

    @Test
    void withFreshnessRetainsSessionAndOtherEvidence() {
        Observation original = observation("session-a");
        Observation historical = original.withFreshness(Freshness.HISTORICAL);
        assertEquals("session-a", historical.serverSession());
        assertEquals(original, historical.withFreshness(Freshness.CURRENT));
        assertEquals(Freshness.CURRENT, original.freshness());
    }

    @Test
    void legacyContextDefaultsToUnknownSession() {
        CollectionContext legacy = new TestSupport.Ctx(WORLD, 42L, now);
        assertEquals("", legacy.serverSession());
        assertEquals("", query.refresh(legacy, REQUEST).observation().serverSession());
    }

    @Test
    void refreshCapturesSessionWithoutChangingRevisionSequence() {
        var first = query.refresh(context("session-a"), REQUEST);
        var next = query.refresh(context("session-b"), REQUEST);
        assertTrue(first.success());
        assertTrue(next.success());
        assertEquals("session-a", first.observation().serverSession());
        assertEquals("session-b", next.observation().serverSession());
        assertEquals(first.observation().revision() + 1L, next.observation().revision());
        assertEquals(next.observation(), store.get(REQUEST.keyFor(WORLD)).orElseThrow());
    }

    @Test
    void asyncRefreshAlsoCapturesSession() {
        var result = query.refreshAsync(context("session-a"), REQUEST, 0L).join();
        assertTrue(result.success());
        assertEquals("session-a", result.observation().serverSession());
    }

    @Test
    void strictReadAllowsOnlyKnownMatchingSessionWithinTtl() {
        query.refresh(context("session-a"), REQUEST);
        ReadResult same = query.read(WORLD, "session-a", REQUEST);
        ReadResult other = query.read(WORLD, "session-b", REQUEST);
        assertEquals(Freshness.CURRENT, same.freshness());
        assertTrue(same.canProveAbsence());
        assertTrue(other.present(), "retain historical evidence rather than erase it");
        assertEquals(Freshness.HISTORICAL, other.freshness());
        assertFalse(other.canProveAbsence());
        assertEquals(Freshness.CURRENT, query.read(WORLD, "session-a", REQUEST).freshness(),
                "strict classification must not mutate the stored snapshot");
        assertEquals(1, provider.invocations.get(), "reads never collect");
    }

    @Test
    void sameSessionStillHonorsTtlAndDifferentSessionIsHistorical() {
        query.refresh(context("session-a"), REQUEST);
        now += 30_001L;
        assertEquals(Freshness.STALE, query.read(WORLD, "session-a", REQUEST).freshness());
        assertEquals(Freshness.HISTORICAL, query.read(WORLD, "session-b", REQUEST).freshness());
    }

    @Test
    void strictReadNeverMatchesUnknownSessionsEvenWhenBothUnknown() {
        for (String stored : new String[]{null, "", " ", "unknown", "session-a"}) {
            query.refresh(context(stored), REQUEST);
            for (String requested : new String[]{null, "", " ", "unknown"}) {
                ReadResult result = query.read(WORLD, requested, REQUEST);
                assertEquals(Freshness.HISTORICAL, result.freshness());
                assertFalse(result.canProveAbsence());
            }
            if (!"session-a".equals(stored)) {
                assertEquals(Freshness.HISTORICAL, query.read(WORLD, "session-a", REQUEST).freshness());
            }
        }
    }

    @Test
    void strictMissingReadDoesNotScanOrFabricateHistory() {
        ReadResult result = query.read(WORLD, "session-a", REQUEST);
        assertFalse(result.present());
        assertEquals(Freshness.UNKNOWN, result.freshness());
        assertEquals(0, provider.invocations.get());
    }

    @Test
    void legacyReadKeepsCompatibilityWhileStrictReadRejectsUnknown() {
        query.refresh(new TestSupport.Ctx(WORLD, 42L, now), REQUEST);
        assertEquals(Freshness.CURRENT, query.read(WORLD, REQUEST).freshness());
        assertEquals(Freshness.HISTORICAL, query.read(WORLD, "session-a", REQUEST).freshness());
    }

    @Test
    void jsonRoundTripRetainsSessionButAlwaysLoadsHistorical() {
        Observation original = observation("session-a");
        String json = ObservationJson.toJson(List.of(original));
        assertTrue(json.contains("\"serverSession\":\"session-a\""));
        var loaded = ObservationJson.fromJson(json);
        assertEquals(List.of(original.withFreshness(Freshness.HISTORICAL)), loaded);
    }

    @Test
    void legacyJsonWithoutSessionLoadsAsUnknownHistorical() {
        String legacy = """
                {"version":1,"observations":[{"worldId":"live","dimension":"minecraft:overworld",
                "topic":"environment","target":"","filter":"","payload":{"dimension":"minecraft:overworld",
                "dayTime":6000,"raining":true,"thundering":false},"source":"environment","origin":"DIRECT",
                "gameTick":42,"epochMs":1000,"coverage":"COMPLETE","revision":7,"dataVersion":1}]}
                """;
        var loaded = ObservationJson.fromJson(legacy);
        assertEquals(1, loaded.size());
        assertEquals("", loaded.getFirst().serverSession());
        assertEquals(Freshness.HISTORICAL, loaded.getFirst().freshness());
        assertEquals(7L, loaded.getFirst().revision());
    }

    @Test
    void sameSessionCannotPromoteDiskLoadedHistoricalObservation() {
        Observation loaded = ObservationJson.fromJson(ObservationJson.toJson(List.of(observation("session-a"))))
                .getFirst();
        store.put(loaded);
        assertEquals(Freshness.HISTORICAL, query.read(WORLD, "session-a", ENVIRONMENT).freshness());
        assertEquals(Freshness.HISTORICAL, query.read(WORLD, ENVIRONMENT).freshness());
    }

    @Test
    void newSessionRefreshReplacesOldSessionWithoutSharingCurrentness() {
        query.refresh(context("session-a"), REQUEST);
        assertEquals(Freshness.HISTORICAL, query.read(WORLD, "session-b", REQUEST).freshness());
        query.refresh(context("session-b"), REQUEST);
        assertEquals(Freshness.CURRENT, query.read(WORLD, "session-b", REQUEST).freshness());
        assertEquals(Freshness.HISTORICAL, query.read(WORLD, "session-a", REQUEST).freshness());
        assertEquals(1, store.size());
    }
}
