package com.dwinovo.numen.worldstate.core.store;

import com.dwinovo.numen.worldstate.core.changes.v2.ChangeEvent;
import com.dwinovo.numen.worldstate.core.changes.v2.ChangeFeed;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 带 v2 变化流的观测存储（041 §18.5）：**分配序号与写入表在同一临界区**。
 *
 * <h2>为什么单独一个类</h2>
 * v1 的 {@link InMemoryObservationStore} 已经接了 {@code ChangeLog}（旧语义），
 * 而 042 R3 要求「不能悄悄改枚举语义、要按 schemaVersion 分流」。所以 v2 单独一份实现，
 * 等能力面接好版本分流后再切换，不从旧类上动刀。
 *
 * <h2>两条纪律</h2>
 * <ul>
 *   <li>{@link #put} 里 {@code map.put} 与 {@code feed.append} 在**同一个 synchronized 块**，
 *       杜绝「序号已发、内容没到」的窗口；</li>
 *   <li>{@link #loadAll} 只填表、**不产生任何变化**——磁盘载入不是世界变化（R3-1）。</li>
 * </ul>
 */
public final class ObservableStoreV2 implements ObservationStore {

    public static final int DEFAULT_CAPACITY = 512;

    private final int capacity;
    private final Map<String, Observation> map;
    private final ChangeFeed feed;

    public ObservableStoreV2(ChangeFeed feed) {
        this(DEFAULT_CAPACITY, feed);
    }

    public ObservableStoreV2(int capacity, ChangeFeed feed) {
        this.capacity = Math.max(1, capacity);
        this.map = new LinkedHashMap<>(16, 0.75f, true);
        this.feed = feed;
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
            return false;                    // 迟到的旧刷新：拒收，且不产生变化
        }
        map.put(k, observation);
        if (feed != null) {
            feed.append(
                    observation.key().topic() == null ? "" : observation.key().topic().id(),
                    k,
                    current == null ? ChangeEvent.Kind.CREATED : ChangeEvent.Kind.UPDATED,
                    observation.revision(),
                    observation.epochMs());
        }
        evictIfNeeded();
        return true;
    }

    /**
     * 从磁盘载入：只填表，不发事件。
     *
     * <p>载入的是历史快照，不是"刚发生的变化"；把它当变化广播出去会污染消费者游标（R3-1）。
     */
    public synchronized void loadAll(List<Observation> observations) {
        if (observations == null) return;
        for (Observation o : observations) {
            if (o == null || o.key() == null) continue;
            map.put(o.key().canonical(), o);
        }
        evictIfNeeded();
    }

    @Override
    public synchronized List<Observation> all() {
        return new ArrayList<>(map.values());
    }

    @Override
    public synchronized int size() {
        return map.size();
    }

    @Override
    public synchronized long maxRevision() {
        long max = 0L;
        for (Observation o : map.values()) {
            if (o != null && o.revision() > max) max = o.revision();
        }
        return max;
    }

    /**
     * 清空缓存 → 每条发 {@link ChangeEvent.Kind#INVALIDATE}（缓存不可信），
     * **不是** {@code GONE}（那表示世界里真的没了）。
     */
    @Override
    public synchronized void clear() {
        if (feed != null) {
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Observation> e : map.entrySet()) {
                feed.append(
                        e.getValue().key().topic() == null ? "" : e.getValue().key().topic().id(),
                        e.getKey(), ChangeEvent.Kind.INVALIDATE, e.getValue().revision(), now);
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
