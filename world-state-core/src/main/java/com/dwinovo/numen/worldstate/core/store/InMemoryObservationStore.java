package com.dwinovo.numen.worldstate.core.store;

import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 内存观测存储：频繁读取的当前观测，带容量上限的 LRU 淘汰。
 *
 * <p>返回的 {@link Observation} 是不可变 record，读侧天然拿到不可变快照，不会读到写一半的数据。
 * 所有操作在同一把锁下完成；{@link #all()} 返回副本。
 *
 * <p>容量淘汰按 LRU；被淘汰只是丢了缓存，不影响任何登记真源（设施登记在 settlement 自己那边）。
 */
public final class InMemoryObservationStore implements ObservationStore {

    public static final int DEFAULT_CAPACITY = 256;

    private final int capacity;
    private final Map<String, Observation> map;
    /** 变化日志（041）：可选；为 null 时只记内存、不发变化。 */
    private final com.dwinovo.numen.worldstate.core.changes.ChangeLog changeLog;
    /** v2 流（R3 过渡期双写；null = 只写 v1）。 */
    private final com.dwinovo.numen.worldstate.core.changes.v2.ChangeFeed changeFeed;
    /**
     * GONE 用的独立序号。
     *
     * <p>为什么不能复用观测修订号：GONE 说的是"这个键没了"，而消费者可能已经看到过
     * 那个键的最后一个 revision——若沿用同一个数，会被 `revision <= after` 直接过滤掉，
     * 消费者永远收不到"没了"。这里用一个只增不减的计数器保证 GONE 一定排在后面。
     */
    private long goneSeq = 0L;

    public InMemoryObservationStore() {
        this(DEFAULT_CAPACITY, null);
    }

    public InMemoryObservationStore(int capacity) {
        this(capacity, null);
    }

    public InMemoryObservationStore(int capacity, com.dwinovo.numen.worldstate.core.changes.ChangeLog changeLog) {
        this(capacity, changeLog, null);
    }

    /**
     * R3 过渡期**双写**（041 §24.4）：同一次被接受的写入，同时进 v1 日志与 v2 流。
     *
     * <p>为什么双写：老消费者仍读 v1，新消费者按版本切 v2；两边都必须看到"被接受的写入"。
     * <b>两份序号不保证对应</b>——这是刻意的，跨版本迁移走 RESET，不试图对齐两份序号。
     *
     * @param changeFeed v2 流（可为 null = 只写 v1）
     */
    public InMemoryObservationStore(int capacity,
                                    com.dwinovo.numen.worldstate.core.changes.ChangeLog changeLog,
                                    com.dwinovo.numen.worldstate.core.changes.v2.ChangeFeed changeFeed) {
        this.capacity = Math.max(1, capacity);
        this.map = new LinkedHashMap<>(16, 0.75f, true); // accessOrder = true → LRU
        this.changeLog = changeLog;
        this.changeFeed = changeFeed;
    }

    @Override
    public synchronized Optional<Observation> get(ObservationKey key) {
        if (key == null) return Optional.empty();
        return Optional.ofNullable(map.get(key.canonical()));
    }

    @Override
    public synchronized boolean put(Observation observation) {
        if (observation == null || observation.key() == null) return false;
        String k = observation.key().canonical();
        Observation current = map.get(k);
        if (current != null && current.revision() > observation.revision()) {
            // 迟到的旧刷新：拒绝覆盖更新的观测。
            return false;
        }
        map.put(k, observation);
        evictIfNeeded();
        if (changeLog != null) {
            // 只有"被接受"的写入才产生变化（041 §3）；上面拒收的分支已经 return false
            changeLog.append(new com.dwinovo.numen.worldstate.core.changes.Change(
                    observation.revision(),
                    observation.key().topic() == null ? "" : observation.key().topic().id(),
                    k,
                    current == null
                            ? com.dwinovo.numen.worldstate.core.changes.Change.Kind.CREATED
                            : com.dwinovo.numen.worldstate.core.changes.Change.Kind.UPDATED,
                    observation.epochMs()));
            if (observation.revision() > goneSeq) goneSeq = observation.revision();
            if (changeFeed != null) {
                // v2 用自己的 streamSeq；两边的"键/主题/修订号/类型"必须逐条一致（一致性测试盯着这条）
                changeFeed.append(
                        observation.key().topic() == null ? "" : observation.key().topic().id(),
                        k,
                        current == null
                                ? com.dwinovo.numen.worldstate.core.changes.v2.ChangeEvent.Kind.CREATED
                                : com.dwinovo.numen.worldstate.core.changes.v2.ChangeEvent.Kind.UPDATED,
                        observation.revision(),
                        observation.epochMs());
            }
        }
        return true;
    }

    @Override
    public synchronized List<Observation> all() {
        return new ArrayList<>(map.values());
    }

    @Override
    public synchronized long maxRevision() {
        long max = 0L;
        for (Observation o : map.values()) {
            if (o != null && o.revision() > max) max = o.revision();
        }
        return max;
    }

    @Override
    public synchronized int size() {
        return map.size();
    }

    @Override
    public synchronized void clear() {
        if (changeLog != null) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Observation> e : map.entrySet()) {
                changeLog.append(new com.dwinovo.numen.worldstate.core.changes.Change(
                        ++goneSeq, e.getValue().key().topic() == null ? "" : e.getValue().key().topic().id(),
                        e.getKey(), com.dwinovo.numen.worldstate.core.changes.Change.Kind.GONE, now));
            }
        }
        if (changeFeed != null) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Observation> e : map.entrySet()) {
                // v1 用 GONE（"这个键没了"）；v2 用 INVALIDATE（"这份缓存不可信"）——
                // 语义不同，一致性测试按"同一批键"对比，不按字面 kind 对比。
                changeFeed.append(
                        e.getValue().key().topic() == null ? "" : e.getValue().key().topic().id(),
                        e.getKey(),
                        com.dwinovo.numen.worldstate.core.changes.v2.ChangeEvent.Kind.INVALIDATE,
                        e.getValue().revision(), now);
            }
        }
        map.clear();
    }

    private void evictIfNeeded() {
        while (map.size() > capacity) {
            String eldest = map.keySet().iterator().next();
            map.remove(eldest);
        }
    }
}
