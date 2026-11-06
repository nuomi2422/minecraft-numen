package com.dwinovo.numen.worldstate.core.store;

import com.dwinovo.numen.worldstate.core.changes.Change;
import com.dwinovo.numen.worldstate.core.changes.InMemoryChangeLog;
import com.dwinovo.numen.worldstate.core.changes.v2.ChangeEvent;
import com.dwinovo.numen.worldstate.core.changes.v2.InMemoryChangeFeed;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Origin;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload;
import com.dwinovo.numen.worldstate.core.observation.payload.SelfPayload;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 定向失效（2026-11-06，SELF 自动采样切片）。
 *
 * <p><b>为什么需要它</b>：身体自己每 10 tick 刷一次 SELF；死亡/换维度时只该丢掉<b>这个主体</b>
 * 的旧帧。若改用 {@code clear()}，箱子/方块观测会跟着没，而且 v1 日志会给每个键发 {@code GONE}
 * ——那是"世界里的东西没了"的语义，跟"这份缓存不可信"不是一回事。
 *
 * <p><b>本测试只盯存储语义</b>：不测采样节拍、不测服务端事件，也不声称 10 tick 够用。
 */
class InMemoryObservationStoreInvalidateTest {

    private static final String WORLD = "world-a";
    private static final String DIM = "minecraft:overworld";
    private static final String SESSION = "session-a";
    private static final UUID COMPANION = UUID.fromString("a43d753e-790d-472b-8d5b-dc18ac0afbc0");
    private static final long EPOCH = 1_700_000_000_000L;

    private static Observation self(String dimension, long revision) {
        ObservationRequest request = ObservationRequest.self(dimension, COMPANION);
        return new Observation(request.keyFor(WORLD),
                new SelfPayload(dimension, COMPANION, null, null, null, null, null, null, null, null, null, null, null),
                "self", Origin.DIRECT, 7L, EPOCH, Coverage.COMPLETE, Freshness.CURRENT, revision, 1, SESSION);
    }

    private static Observation environment(long revision) {
        ObservationRequest request = ObservationRequest.environment(DIM);
        return new Observation(request.keyFor(WORLD),
                new EnvironmentPayload(DIM, 6000L, false, false),
                "env", Origin.DIRECT, 7L, EPOCH, Coverage.COMPLETE, Freshness.CURRENT, revision, 1, SESSION);
    }

    @Test
    void invalidateRemovesExactlyThatKeyAndEmitsV2InvalidateWithoutV1Gone() {
        InMemoryChangeLog v1 = new InMemoryChangeLog(64, 60_000L, () -> 5_000L);
        InMemoryChangeFeed v2 = new InMemoryChangeFeed("epoch-1", 64, 60_000L, () -> 5_000L);
        InMemoryObservationStore store = new InMemoryObservationStore(16, v1, v2);
        Observation self = self(DIM, 1);
        Observation environment = environment(2);
        assertTrue(store.put(self));
        assertTrue(store.put(environment));

        assertTrue(store.invalidate(self.key()));

        assertEquals(1, store.size());
        assertTrue(store.get(self.key()).isEmpty());
        assertEquals(environment, store.get(environment.key()).orElseThrow(), "其它主题必须一条不动");

        // v1：只有两次写入的 CREATED，没有任何 GONE（失效不是"世界里的东西没了"）。
        var v1Changes = v1.after(0L, Set.of(), 0).changes();
        assertEquals(2, v1Changes.size());
        assertTrue(v1Changes.stream().noneMatch(c -> c.kind() == Change.Kind.GONE));
        assertTrue(v1Changes.stream().anyMatch(c -> c.kind() == Change.Kind.CREATED
                && c.key().equals(environment.key().canonical())));

        // v2：必须看得到 INVALIDATE，键/修订号/时间戳取自被删的那条观测。
        var page = v2.query(0L, "epoch-1", null, 0);
        assertEquals(3, page.events().size());
        ChangeEvent invalidated = page.events().get(2);
        assertEquals(ChangeEvent.Kind.INVALIDATE, invalidated.kind());
        assertEquals(self.key().canonical(), invalidated.key());
        assertEquals("self", invalidated.topic());
        assertEquals(1L, invalidated.observationRevision());
        assertEquals(EPOCH, invalidated.epochMs());
        // 同一条 METADATA 里的环境事件仍是 CREATED，没被这次失效带成 INVALIDATE。
        assertEquals(ChangeEvent.Kind.CREATED, page.events().get(1).kind());
    }

    @Test
    void invalidateDoesNotFireForAbsentOrNullKey() {
        InMemoryChangeLog v1 = new InMemoryChangeLog(64, 60_000L, () -> 5_000L);
        InMemoryChangeFeed v2 = new InMemoryChangeFeed("epoch-1", 64, 60_000L, () -> 5_000L);
        InMemoryObservationStore store = new InMemoryObservationStore(16, v1, v2);
        assertTrue(store.put(environment(1)));
        long watermark = v2.watermark();
        int v1Size = v1.size();

        // 键不存在（换了个维度就是另一把键）：返回 false，且不许伪造一条事件。
        assertFalse(store.invalidate(ObservationRequest.self("minecraft:the_end", COMPANION).keyFor(WORLD)));
        assertFalse(store.invalidate(null));

        assertEquals(watermark, v2.watermark());
        assertEquals(v1Size, v1.size());
        assertEquals(1, store.size());
        // 二次失效同一个键：已经删掉了，不发第二条。
        Observation self = self(DIM, 2);
        store.put(self);
        assertTrue(store.invalidate(self.key()));
        long afterFirst = v2.watermark();
        assertFalse(store.invalidate(self.key()));
        assertEquals(afterFirst, v2.watermark());
    }
}
