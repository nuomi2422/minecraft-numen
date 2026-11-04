package com.dwinovo.numen.worldstate.core.changes;

import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Origin;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload;
import com.dwinovo.numen.worldstate.core.store.InMemoryObservationStore;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 041 U4a：变化日志的四条硬规矩。
 *
 * <p>①只有被接受的写入产生变化；②游标单调；③断档显式 gap；④GONE 不能被游标过滤掉。
 */
class InMemoryChangeLogTest {

    private static final String DIM = "minecraft:overworld";

    private static Observation obs(String target, long revision, long epochMs) {
        EnvironmentPayload payload = new EnvironmentPayload(DIM, 1000L, false, false);
        ObservationKey key = ObservationKey.of("live", DIM, Topic.BLOCKS, target, "block=minecraft:chest");
        return new Observation(key, payload, "blocks", Origin.DIRECT, 1L, epochMs,
                Coverage.COMPLETE, Freshness.CURRENT, revision, 1);
    }

    @Test
    void acceptedWritesProduceCreatedThenUpdated() {
        InMemoryChangeLog log = new InMemoryChangeLog(16, 60_000L, () -> 1_000_000L);
        InMemoryObservationStore store = new InMemoryObservationStore(16, log);

        store.put(obs("box=1", 5L, 1_000_000L));
        store.put(obs("box=1", 6L, 1_000_000L));

        // 从 0 开始、而日志里最早是 revision 5 → 前面那段确实没记过，必须报 gap（不冒充"没变化"）
        ChangesResult fromZero = log.after(0L, null, 10);
        assertEquals(2, fromZero.changes().size());
        assertEquals(Change.Kind.CREATED, fromZero.changes().get(0).kind());
        assertEquals(Change.Kind.UPDATED, fromZero.changes().get(1).kind());
        assertEquals(6L, fromZero.nextCursor());
        assertTrue(fromZero.gap(), "从 0 起读但日志最早是 5 → 前面的历史未知，应报 gap");

        // 从"紧邻最早那条之前"接着读 → 连续，不报 gap
        ChangesResult continuous = log.after(4L, null, 10);
        assertEquals(2, continuous.changes().size());
        assertFalse(continuous.gap());
    }

    @Test
    void rejectedWriteProducesNoChange() {
        InMemoryChangeLog log = new InMemoryChangeLog(16, 60_000L, () -> 1_000_000L);
        InMemoryObservationStore store = new InMemoryObservationStore(16, log);

        store.put(obs("box=1", 9L, 1_000_000L));
        boolean accepted = store.put(obs("box=1", 3L, 1_000_000L));   // 更旧的修订号 → 拒收

        assertFalse(accepted);
        assertEquals(1, log.size(), "被拒收的写入不许产生变化");
    }

    @Test
    void cursorAdvancesAndRespectsLimitAndTopics() {
        InMemoryChangeLog log = new InMemoryChangeLog(16, 60_000L, () -> 1_000_000L);
        InMemoryObservationStore store = new InMemoryObservationStore(16, log);
        for (int i = 1; i <= 5; i++) store.put(obs("box=" + i, i, 1_000_000L));

        ChangesResult page1 = log.after(0L, null, 2);
        assertEquals(2, page1.changes().size());
        assertEquals(2L, page1.nextCursor());

        ChangesResult page2 = log.after(page1.nextCursor(), null, 10);
        assertEquals(3, page2.changes().size(), "接着上一页继续，不重复也不漏");

        ChangesResult filtered = log.after(0L, Set.of("environment"), 10);
        assertEquals(0, filtered.changes().size(), "主题过滤要生效");
    }

    @Test
    void evictedHistoryReportsGapNotEmptyList() {
        AtomicLong now = new AtomicLong(1_000_000L);
        InMemoryChangeLog log = new InMemoryChangeLog(2, 60_000L, now::get);
        InMemoryObservationStore store = new InMemoryObservationStore(16, log);
        for (int i = 1; i <= 4; i++) store.put(obs("box=" + i, i, now.get()));

        ChangesResult r = log.after(0L, null, 10);   // 0 之后的历史已被容量挤掉
        assertTrue(r.gap(), "断档必须显式说明，不能返回空列表冒充'没变化'");
        assertTrue(r.oldestRetainedRevision() >= 3, "保留窗口应只剩最近两条");
    }

    @Test
    void timeWindowEvictsOldChanges() {
        AtomicLong now = new AtomicLong(1_000_000L);
        InMemoryChangeLog log = new InMemoryChangeLog(64, 1_000L, now::get);
        InMemoryObservationStore store = new InMemoryObservationStore(16, log);
        store.put(obs("box=1", 1L, now.get()));
        now.addAndGet(5_000L);   // 超过时间窗
        store.put(obs("box=2", 2L, now.get()));

        ChangesResult r = log.after(0L, null, 10);
        assertTrue(r.gap(), "过期的那段应被时间窗淘汰并报 gap");
        assertEquals(1, r.changes().size());
    }

    @Test
    void goneSurvivesCursorFilter() {
        InMemoryChangeLog log = new InMemoryChangeLog(16, 60_000L, () -> 1_000_000L);
        InMemoryObservationStore store = new InMemoryObservationStore(16, log);
        store.put(obs("box=1", 5L, 1_000_000L));

        ChangesResult seen = log.after(0L, null, 10);
        long cursor = seen.nextCursor();       // 消费者已看到 revision 5
        store.clear();

        ChangesResult afterClear = log.after(cursor, null, 10);
        assertEquals(1, afterClear.changes().size(), "GONE 不能被'已看过 revision'过滤掉");
        assertEquals(Change.Kind.GONE, afterClear.changes().get(0).kind());
    }
}
