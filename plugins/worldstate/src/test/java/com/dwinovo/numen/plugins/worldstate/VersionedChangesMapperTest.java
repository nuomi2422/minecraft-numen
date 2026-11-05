package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.ChangesQuery;
import com.dwinovo.numen.api.observation.ChangesV2Response;
import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.worldstate.core.changes.v2.ChangeEvent;
import com.dwinovo.numen.worldstate.core.changes.v2.InMemoryChangeFeed;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 042 R3：版本化 changes 的分流规则（纯函数，无需 MC）。 */
class VersionedChangesMapperTest {

    private static InMemoryChangeFeed feed() {
        AtomicLong clock = new AtomicLong(1_000L);
        return new InMemoryChangeFeed("e1", 16, 60_000L, clock::get);
    }

    @Test
    void v1RequestIsRejectedExplicitly() {
        var r = VersionedChangesMapper.map(new ChangesQuery(1, 0, null, List.of(), 10), feed());
        assertEquals(WorldObservationService.Status.ERROR, r.status(), "v1 不许静默走 v2");
        assertTrue(r.reason().contains("changes(after"), r.reason());
    }

    @Test
    void missingEpochRequiresReset() {
        InMemoryChangeFeed f = feed();
        f.append("blocks", "k1", ChangeEvent.Kind.CREATED, 1L, 1_000L);
        ChangesV2Response r = VersionedChangesMapper.map(
                new ChangesQuery(2, 1L, "", List.of(), 10), f);
        assertEquals(WorldObservationService.Status.OK, r.status());
        assertTrue(r.resetRequired(), "缺 epoch 必须 reset，不能默认当前 epoch");
        assertEquals(f.watermark(), r.nextCursor());
        assertTrue(r.events().isEmpty());
    }

    @Test
    void v2MapsEventsAndCursor() {
        InMemoryChangeFeed f = feed();
        f.append("blocks", "k1", ChangeEvent.Kind.CREATED, 1L, 1_000L);
        f.append("blocks", "k2", ChangeEvent.Kind.UPDATED, 2L, 1_001L);
        ChangesV2Response r = VersionedChangesMapper.map(
                new ChangesQuery(2, 0L, "e1", List.of(), 10), f);
        assertEquals(2, r.events().size());
        assertEquals("CREATED", r.events().get(0).kind());
        assertEquals(1L, r.events().get(0).streamSeq());
        assertEquals(2L, r.nextCursor());
        assertFalse(r.resetRequired());
        assertEquals("e1", r.streamEpoch());
    }

    @Test
    void topicFilterStillAdvancesCursor() {
        InMemoryChangeFeed f = feed();
        f.append("environment", "e1", ChangeEvent.Kind.UPDATED, 1L, 1_000L);
        f.append("blocks", "b1", ChangeEvent.Kind.UPDATED, 2L, 1_001L);
        ChangesV2Response r = VersionedChangesMapper.map(
                new ChangesQuery(2, 0L, "e1", List.of("containers"), 10), f);
        assertTrue(r.events().isEmpty());
        assertEquals(2L, r.nextCursor(), "全被过滤也要推进，否则消费者卡死");
    }

    @Test
    void nullFeedIsErrorNotEmpty() {
        var r = VersionedChangesMapper.map(ChangesQuery.v2(0, "e1", List.of(), 10), null);
        assertEquals(WorldObservationService.Status.ERROR, r.status(), "拿不到增量 ≠ 没有变化");
    }

    @Test
    void nullQueryIsError() {
        assertEquals(WorldObservationService.Status.ERROR, VersionedChangesMapper.map(null, feed()).status());
    }

    @Test
    void wrongEpochRequiresReset() {
        InMemoryChangeFeed f = feed();
        f.append("blocks", "k1", ChangeEvent.Kind.CREATED, 1L, 1_000L);
        ChangesV2Response r = VersionedChangesMapper.map(
                new ChangesQuery(2, 0L, "old-epoch", List.of(), 10), f);
        assertTrue(r.resetRequired(), "epoch 不匹配必须 reset");
    }
    @Test
    void unknownSchemaVersionIsErrorNotV1() {
        var r = VersionedChangesMapper.map(new ChangesQuery(99, 0, null, List.of(), 10), feed());
        assertEquals(WorldObservationService.Status.ERROR, r.status(), "未知版本必须 ERROR");
        assertTrue(r.reason().contains("unsupported schemaVersion=99"), r.reason());
        assertFalse(r.reason().contains("must use changes(after"), "99 不该被当成 v1 的提示");
    }
}
