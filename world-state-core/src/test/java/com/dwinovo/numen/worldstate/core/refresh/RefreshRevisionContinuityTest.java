package com.dwinovo.numen.worldstate.core.refresh;

import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Origin;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.observation.payload.EnvironmentPayload;
import com.dwinovo.numen.worldstate.core.store.InMemoryObservationStore;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-05 实机事故：进程重启后，磁盘里载入的观测修订号是几十上百，而新进程的序列号从 0 起，
 * `put()` 按"新观测更旧"把**每一条刷新都静默丢弃**——表现是"刷新成功但观测永远不更新"。
 *
 * <p>修法：`RefreshService` 构造时把序列号接在存储最大修订号之后。这里用最大修订号验证续号。
 */
class RefreshRevisionContinuityTest {

    private static Observation sample(long revision) {
        EnvironmentPayload payload = new EnvironmentPayload("minecraft:overworld", 1000L, false, false);
        ObservationKey key = ObservationKey.of("live", "minecraft:overworld", Topic.ENVIRONMENT, "", "");
        return new Observation(key, payload, "environment", Origin.DIRECT, 1L, 1_700_000_000_000L,
                Coverage.COMPLETE, Freshness.HISTORICAL, revision, 1);
    }

    @Test
    void loadedRevisionsAreVisibleAsMax() {
        InMemoryObservationStore store = new InMemoryObservationStore(16);
        store.put(sample(252L));
        store.put(sample(7L));   // 同一个键，更大的修订号会覆盖
        assertEquals(252L, store.maxRevision(), "最大修订号要能反映磁盘载入的高位");
    }

    @Test
    void newServiceContinuesFromStoredMaxNotFromZero() {
        InMemoryObservationStore store = new InMemoryObservationStore(16);
        store.put(sample(252L));
        RefreshService svc = new RefreshService(null, store, Runnable::run);
        // 直接验证内部续号：用反射读 sequence，避免依赖采集器
        long seeded = seededValue(svc);
        assertEquals(252L, seeded, "序列号必须接在存储最大修订号之后，否则 put() 会拒收");
    }

    @Test
    void staleRevisionWouldBeRejectedByStore() {
        InMemoryObservationStore store = new InMemoryObservationStore(16);
        store.put(sample(252L));
        boolean accepted = store.put(sample(1L));
        assertTrue(!accepted, "低修订号必须被拒——这正是事故里被拒的新刷新");
    }

    private static long seededValue(RefreshService svc) {
        try {
            java.lang.reflect.Field f = RefreshService.class.getDeclaredField("sequence");
            f.setAccessible(true);
            return ((java.util.concurrent.atomic.AtomicLong) f.get(svc)).get();
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
