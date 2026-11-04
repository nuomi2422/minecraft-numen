package com.dwinovo.numen.worldstate.core.store;

import com.dwinovo.numen.worldstate.core.changes.v2.ChangeEvent;
import com.dwinovo.numen.worldstate.core.changes.v2.ChangesPage;
import com.dwinovo.numen.worldstate.core.changes.v2.InMemoryChangeFeed;
import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Origin;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 041 §18.5 + R3-1/R3-3：同锁写入、载入不产生事件、接缝可见、失效用 INVALIDATE。 */
class ObservableStoreV2Test {

    private static final String DIM = "minecraft:overworld";

    private static Observation obs(String target, long revision) {
        EnvironmentPayload payload = new EnvironmentPayload(DIM, 1000L, false, false);
        ObservationKey key = ObservationKey.of("live", DIM, Topic.BLOCKS, target, "block=minecraft:chest");
        return new Observation(key, payload, "blocks", Origin.DIRECT, 1L, 1_700_000_000_000L,
                Coverage.COMPLETE, Freshness.CURRENT, revision, 1);
    }

    @Test
    void putEmitsCreatedThenUpdatedWithMonotonicSeq() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed feed = new InMemoryChangeFeed("e1", 16, 60_000L, clock::get);
        ObservableStoreV2 store = new ObservableStoreV2(16, feed);

        store.put(obs("box=1", 5));
        store.put(obs("box=1", 6));

        ChangesPage page = feed.query(0, "e1", null, 10);
        assertEquals(2, page.events().size());
        assertEquals(ChangeEvent.Kind.CREATED, page.events().get(0).kind());
        assertEquals(ChangeEvent.Kind.UPDATED, page.events().get(1).kind());
        assertEquals(page.events().get(0).streamSeq() + 1, page.events().get(1).streamSeq());
    }

    @Test
    void rejectedWriteEmitsNothing() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed feed = new InMemoryChangeFeed("e1", 16, 60_000L, clock::get);
        ObservableStoreV2 store = new ObservableStoreV2(16, feed);

        store.put(obs("box=1", 9));
        boolean accepted = store.put(obs("box=1", 3));
        assertFalse(accepted);
        assertEquals(1, feed.query(0, "e1", null, 10).events().size(),
                "被拒收的写入不许产生变化");
    }

    @Test
    void loadAllDoesNotFakeHistory() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed feed = new InMemoryChangeFeed("e1", 16, 60_000L, clock::get);
        ObservableStoreV2 store = new ObservableStoreV2(16, feed);

        store.loadAll(List.of(obs("box=1", 11), obs("box=2", 12)));

        assertEquals(2, store.size());
        assertEquals(0, feed.query(0, "e1", null, 10).events().size(),
                "磁盘载入不是世界变化，不能伪造 CREATED");
    }

    @Test
    void watermarkThenAppendIsVisibleToNextQuery() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed feed = new InMemoryChangeFeed("e1", 16, 60_000L, clock::get);
        ObservableStoreV2 store = new ObservableStoreV2(16, feed);

        store.put(obs("box=1", 1));
        long watermark = feed.watermark();          // 快照与水位同一时刻拿到
        store.put(obs("box=2", 2));                  // 快照之后、changes 之前写入

        ChangesPage page = feed.query(watermark, "e1", null, 10);
        assertEquals(1, page.events().size(), "接缝里写入的事件必须出现在下一次查询");
        assertEquals(2L, page.events().get(0).observationRevision());
    }

    @Test
    void sameRevisionIsRejectedAndEmitsNothing() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed feed = new InMemoryChangeFeed("e1", 16, 60_000L, clock::get);
        ObservableStoreV2 store = new ObservableStoreV2(16, feed);

        assertTrue(store.put(obs("box=1", 5)));
        assertFalse(store.put(obs("box=1", 5)), "同 revision 重复写入必须拒收");
        assertEquals(1, feed.query(0, "e1", null, 10).events().size(),
                "同 revision 不能产生第二个事件");
    }

    @Test
    void evictionEmitsInvalidate() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed feed = new InMemoryChangeFeed("e1", 16, 60_000L, clock::get);
        ObservableStoreV2 store = new ObservableStoreV2(2, feed);

        store.put(obs("box=1", 1));
        store.put(obs("box=2", 2));
        store.put(obs("box=3", 3));      // 容量 2 → box=1 被挤掉

        ChangesPage page = feed.query(0, "e1", null, 10);
        long invalidates = page.events().stream()
                .filter(e -> e.kind() == ChangeEvent.Kind.INVALIDATE).count();
        assertEquals(1, invalidates, "被淘汰的观测必须发 INVALIDATE");
        assertEquals(2, store.size());
    }

    @Test
    void clearEmitsInvalidateNotGone() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed feed = new InMemoryChangeFeed("e1", 16, 60_000L, clock::get);
        ObservableStoreV2 store = new ObservableStoreV2(16, feed);

        store.put(obs("box=1", 1));
        long cursor = feed.watermark();
        store.clear();

        ChangesPage page = feed.query(cursor, "e1", null, 10);
        assertEquals(1, page.events().size());
        assertEquals(ChangeEvent.Kind.INVALIDATE, page.events().get(0).kind(),
                "清空缓存是 INVALIDATE，不是世界里没了");
        assertTrue(store.size() == 0);
    }
}
