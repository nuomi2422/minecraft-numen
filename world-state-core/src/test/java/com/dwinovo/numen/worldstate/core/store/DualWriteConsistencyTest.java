package com.dwinovo.numen.worldstate.core.store;

import com.dwinovo.numen.worldstate.core.changes.Change;
import com.dwinovo.numen.worldstate.core.changes.ChangesResult;
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

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * R3 过渡期双写的一致性（042 R3 / 041 §24.4）。
 *
 * <p>双写最大的风险不是"序号对不上"，是**有一边悄悄少记**。所以这里逐条比对：
 * 同一批被接受的写入，v1 与 v2 的 (topic, key, revision, kind) 必须一一对应。
 *
 * <p>这条测试在 world-state-core 里跑——**不需要构造 WorldStateService**（它依赖 MC，离线起不来）。
 */
class DualWriteConsistencyTest {

    private static final String DIM = "minecraft:overworld";

    private static Observation obs(String target, Topic topic, long revision) {
        EnvironmentPayload payload = new EnvironmentPayload(DIM, 1000L, false, false);
        ObservationKey key = ObservationKey.of("live", DIM, topic, target, "k=" + target);
        // 时间必须"现在"：v1/v2 都有保留窗口（10 分钟），用 2023 的时间戳会被当场淘汰
        return new Observation(key, payload, "x", Origin.DIRECT, 1L, System.currentTimeMillis(),
                Coverage.COMPLETE, Freshness.CURRENT, revision, 1);
    }

    @Test
    void sameBatchProducesIdenticalEventsOnBothSides() {
        InMemoryChangeLog v1 = new InMemoryChangeLog();
        InMemoryChangeFeed v2 = new InMemoryChangeFeed("e1");
        InMemoryObservationStore store = new InMemoryObservationStore(16, v1, v2);

        // 三条被接受（其中一条同键更新）
        store.put(obs("box=1", Topic.BLOCKS, 5));
        store.put(obs("box=2", Topic.BLOCKS, 6));
        store.put(obs("box=1", Topic.BLOCKS, 7));
        // 一条被拒收（更旧的修订号）——两边都不许出现
        assertFalse(store.put(obs("box=1", Topic.BLOCKS, 3)));

        ChangesResult r1 = v1.after(0, null, 100);
        ChangesPage r2 = v2.query(0, "e1", null, 100);

        assertEquals(3, r1.changes().size(), "v1 事件数");
        assertEquals(3, r2.events().size(), "v2 事件数必须与 v1 相同");
        assertFalse(r2.resetRequired());

        List<String> a = new ArrayList<>();
        for (Change c : r1.changes()) {
            a.add(c.topic() + "|" + c.key() + "|" + c.revision() + "|" + c.kind());
        }
        List<String> b = new ArrayList<>();
        for (ChangeEvent e : r2.events()) {
            b.add(e.topic() + "|" + e.key() + "|" + e.observationRevision() + "|" + e.kind());
        }
        assertEquals(a, b, "两边的 (topic|key|revision|kind) 必须逐条一致（序号可以不同）");
    }

    @Test
    void clearMarksEveryKeyOnBothSides() {
        InMemoryChangeLog v1 = new InMemoryChangeLog();
        InMemoryChangeFeed v2 = new InMemoryChangeFeed("e1");
        InMemoryObservationStore store = new InMemoryObservationStore(16, v1, v2);

        store.put(obs("box=1", Topic.BLOCKS, 1));
        store.put(obs("box=2", Topic.BLOCKS, 2));
        store.clear();

        ChangesResult r1 = v1.after(0, null, 100);
        ChangesPage r2 = v2.query(0, "e1", null, 100);

        long v1Gone = r1.changes().stream().filter(c -> c.kind() == Change.Kind.GONE).count();
        long v2Invalidate = r2.events().stream()
                .filter(e -> e.kind() == ChangeEvent.Kind.INVALIDATE).count();
        assertEquals(2, v1Gone, "v1 每个键一条 GONE");
        assertEquals(2, v2Invalidate, "v2 每个键一条 INVALIDATE（语义不同，数量必须对得上）");
    }
}
