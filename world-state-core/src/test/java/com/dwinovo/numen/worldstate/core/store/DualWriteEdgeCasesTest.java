package com.dwinovo.numen.worldstate.core.store;

import com.dwinovo.numen.worldstate.core.changes.InMemoryChangeLog;
import com.dwinovo.numen.worldstate.core.changes.v2.ChangeEvent;
import com.dwinovo.numen.worldstate.core.changes.v2.ChangesPage;
import com.dwinovo.numen.worldstate.core.changes.v2.InMemoryChangeFeed;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Origin;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 审计实测出的两个洞：v2 写入不能依赖 v1 存在；淘汰的不对称要显式断言。 */
class DualWriteEdgeCasesTest {

    private static final String DIM = "minecraft:overworld";

    private static Observation obs(String target, long revision) {
        EnvironmentPayload payload = new EnvironmentPayload(DIM, 1000L, false, false);
        ObservationKey key = ObservationKey.of("live", DIM, Topic.BLOCKS, target, "k=" + target);
        return new Observation(key, payload, "x", Origin.DIRECT, 1L, System.currentTimeMillis(),
                Coverage.COMPLETE, Freshness.CURRENT, revision, 1);
    }

    @Test
    void v2ReceivesEventsEvenWhenV1IsAbsent() {
        InMemoryChangeFeed v2 = new InMemoryChangeFeed("e1");
        InMemoryObservationStore store = new InMemoryObservationStore(16, null, v2);

        store.put(obs("box=1", 1));

        ChangesPage page = v2.query(0, "e1", null, 10);
        assertEquals(1, page.events().size(), "只给 v2 不给 v1 也必须收到事件（原实现嵌在 v1 分支里，静默丢）");
        assertEquals(ChangeEvent.Kind.CREATED, page.events().get(0).kind());
    }

    @Test
    void evictionIsAsymmetricByDesign() {
        InMemoryChangeLog v1 = new InMemoryChangeLog();
        InMemoryChangeFeed v2 = new InMemoryChangeFeed("e1");
        InMemoryObservationStore store = new InMemoryObservationStore(2, v1, v2);

        store.put(obs("box=1", 1));
        store.put(obs("box=2", 2));
        store.put(obs("box=3", 3));      // 容量 2 → box=1 被淘汰

        long v1Events = v1.after(0, null, 100).changes().size();
        ChangesPage p2 = v2.query(0, "e1", null, 100);
        long v2Invalidate = p2.events().stream()
                .filter(e -> e.kind() == ChangeEvent.Kind.INVALIDATE).count();

        assertEquals(3, v1Events, "v1 只有三条写入（协议表达不了 INVALIDATE，淘汰不发）");
        assertEquals(4, p2.events().size(), "v2 三条写入 + 一条 INVALIDATE");
        assertEquals(1, v2Invalidate, "被淘汰的那条必须发 INVALIDATE");
        assertTrue(v1Events < p2.events().size(), "不对称是刻意的：v1 少一条、v2 多一条");
    }
    @Test
    void evictionInvalidateUsesTheRemovedObservationTime() {
        InMemoryChangeFeed v2 = new InMemoryChangeFeed("e1");
        InMemoryObservationStore store = new InMemoryObservationStore(1, null, v2);
        Observation first = obs("box=1", 1);
        store.put(first);
        store.put(obs("box=2", 2));      // box=1 被淘汰

        var inval = v2.query(0, "e1", null, 10).events().stream()
                .filter(e -> e.kind() == ChangeEvent.Kind.INVALIDATE).findFirst().orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(first.epochMs(), inval.epochMs(),
                "淘汰事件的 epochMs 必须沿用被淘汰那条，不能另取 System.currentTimeMillis()");
    }
}
