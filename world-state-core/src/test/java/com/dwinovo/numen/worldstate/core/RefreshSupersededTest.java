package com.dwinovo.numen.worldstate.core;

import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.FailReason;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Origin;
import com.dwinovo.numen.worldstate.core.model.RefreshStatus;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.observation.payload.EntityCountPayload;
import com.dwinovo.numen.worldstate.core.provider.ProviderRegistry;
import com.dwinovo.numen.worldstate.core.refresh.RefreshResult;
import com.dwinovo.numen.worldstate.core.refresh.RefreshService;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import com.dwinovo.numen.worldstate.core.store.InMemoryObservationStore;
import com.dwinovo.numen.worldstate.core.store.ObservationStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 008「刷新迟到与到期瞬间」：被原库拒收的迟到采集，不能以本次值返回。
 * 旧实现 {@code store.put(...)} 不看返回值，直接把刚采的旧 revision 当当前值回。
 */
class RefreshSupersededTest {

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    private static ObservationRequest sheepBox() {
        return ObservationRequest.entities("minecraft:overworld", Bounds.of(0, 64, 0, 3, 64, 3),
                "minecraft:sheep", null);
    }

    private static EntityCountPayload payload(int count) {
        return new EntityCountPayload("minecraft:overworld", Bounds.at(0, 64, 0),
                "minecraft:sheep", null, count, 1, 0);
    }

    @Test
    void lateRefreshReturnsAcceptedObservationAndSaysItWasSuperseded() {
        InMemoryObservationStore store = new InMemoryObservationStore();
        ProviderRegistry registry = new ProviderRegistry();
        TestSupport.FakeEntitiesProvider provider = new TestSupport.FakeEntitiesProvider();
        registry.register(provider);
        RefreshService service = new RefreshService(registry, store, executor);

        ObservationKey key = sheepBox().keyFor("live");
        // 另一个写入方（并发刷新/重放）已把同键推到 revision 5，且内容是 9 只。
        assertTrue(store.put(TestSupport.observation(key, payload(9), 1_000L,
                Coverage.COMPLETE, Freshness.CURRENT, 5L)));

        RefreshResult result = service.refreshNow(new TestSupport.Ctx("live", 42L, 2_000L), sheepBox());

        assertTrue(result.success(), "被取代时应返回被接受的那份，而不是失败");
        assertEquals(5L, result.observation().revision(), "不得回本次采集的旧 revision");
        assertEquals(9, ((EntityCountPayload) result.observation().payload()).count(),
                "不得把本次采集的 2 只当成当前值");
        assertTrue(result.message().contains("superseded"), "调用方要能看出本次被取代");
        assertEquals(5L, store.get(key).orElseThrow().revision(), "存储里的新值不能被旧刷新覆盖");
    }

    @Test
    void rejectedAndUnreadableAcceptedObservationFailsExplicitly() {
        // 存储拒收且读不到被接受的那份：只能明确报 SUPERSEDED，不能编一个当前值。
        ObservationStore store = new ObservationStore() {
            @Override public Optional<Observation> get(ObservationKey key) { return Optional.empty(); }
            @Override public boolean put(Observation observation) { return false; }
            @Override public List<Observation> all() { return List.of(); }
            @Override public long maxRevision() { return 0L; }
            @Override public int size() { return 0; }
            @Override public void clear() { }
        };
        ProviderRegistry registry = new ProviderRegistry();
        registry.register(new TestSupport.FakeEntitiesProvider());
        RefreshService service = new RefreshService(registry, store, executor);

        RefreshResult result = service.refreshNow(new TestSupport.Ctx("live", 42L, 1_000L), sheepBox());

        assertFalse(result.success());
        assertEquals(RefreshStatus.FAILED, result.status());
        assertEquals(FailReason.SUPERSEDED, result.reason());
    }
}
