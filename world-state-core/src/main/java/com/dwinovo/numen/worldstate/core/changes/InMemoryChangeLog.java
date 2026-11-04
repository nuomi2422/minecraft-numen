package com.dwinovo.numen.worldstate.core.changes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * 内存变化日志：环形缓冲 + 时间窗（041 §5）。
 *
 * <p>两条淘汰规则都要：
 * <ul>
 *   <li><b>容量</b>：条数超过 {@code capacity} 丢最旧的——快变化场景不撑爆内存；</li>
 *   <li><b>时间窗</b>：早于 {@code now - windowMs} 的丢掉——慢变化场景不保留"十分钟前的可读性假象"。</li>
 * </ul>
 *
 * <p><b>GAP 判定</b>：请求的 {@code after} 小于 {@code oldest-1} 时，说明中间那段已经被淘汰，
 * 明确返回 {@code gap=true}；**绝不返回空列表冒充"没有变化"**。
 *
 * <p>线程：所有方法 synchronized，调用方可以跨线程用（存储写入在服务端线程，读可能在别处）。
 */
public final class InMemoryChangeLog implements ChangeLog {

    public static final int DEFAULT_CAPACITY = 512;
    public static final long DEFAULT_WINDOW_MS = 10 * 60 * 1000L;   // 10 分钟

    private final int capacity;
    private final long windowMs;
    private final LongSupplier nowMs;
    private final Deque<Change> entries = new ArrayDeque<>();

    public InMemoryChangeLog() {
        this(DEFAULT_CAPACITY, DEFAULT_WINDOW_MS, System::currentTimeMillis);
    }

    public InMemoryChangeLog(int capacity, long windowMs, LongSupplier nowMs) {
        this.capacity = Math.max(1, capacity);
        this.windowMs = Math.max(1L, windowMs);
        this.nowMs = nowMs == null ? System::currentTimeMillis : nowMs;
    }

    @Override
    public synchronized void append(Change change) {
        if (change == null) return;
        evictExpired();
        entries.addLast(change);
        while (entries.size() > capacity) entries.removeFirst();
    }

    @Override
    public synchronized ChangesResult after(long after, Set<String> topics, int limit) {
        evictExpired();
        if (entries.isEmpty()) {
            // 一条记录都没有：只有"从头开始"的消费者能拿到空结果，其余都是断档
            boolean gapEmpty = after > 0;
            return new ChangesResult(List.of(), after, gapEmpty, 0L);
        }
        long oldest = entries.peekFirst().revision();
        boolean gap = after < oldest - 1;
        int max = limit <= 0 ? Integer.MAX_VALUE : limit;
        List<Change> out = new ArrayList<>();
        for (Change c : entries) {
            if (c.revision() <= after) continue;
            if (topics != null && !topics.isEmpty() && !topics.contains(c.topic())) continue;
            out.add(c);
            if (out.size() >= max) break;
        }
        long next = out.isEmpty() ? Math.max(after, gap ? oldest - 1 : after) : out.get(out.size() - 1).revision();
        return new ChangesResult(out, Math.max(after, next), gap, oldest);
    }

    @Override
    public synchronized long oldestRetainedRevision() {
        evictExpired();
        return entries.isEmpty() ? 0L : entries.peekFirst().revision();
    }

    @Override
    public synchronized int size() {
        evictExpired();
        return entries.size();
    }

    private void evictExpired() {
        long cutoff = nowMs.getAsLong() - windowMs;
        while (!entries.isEmpty() && entries.peekFirst().epochMs() < cutoff) {
            entries.removeFirst();
        }
    }
}
