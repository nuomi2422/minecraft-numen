package com.dwinovo.numen.worldstate.core.changes.v2;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.function.LongSupplier;

/**
 * v2 内存变化日志：自己的单调序号 + epoch + 有界保留 + 分页推进。
 *
 * <h2>与 v1 的差别（042 R3）</h2>
 * <ul>
 *   <li><b>序号独立</b>：{@code nextSeq} 只属于本日志，不复用观测修订号；</li>
 *   <li><b>epoch</b>：进程重启换 epoch，旧游标一律 {@code resetRequired}；</li>
 *   <li><b>分页推进</b>：{@code nextCursor} = 最后一条**检查过**的事件，topic 过滤不会卡死；</li>
 *   <li><b>失效语义</b>：{@code INVALIDATE} 表示缓存不可信，不冒充"世界消失"。</li>
 * </ul>
 */
public final class InMemoryChangeFeed implements ChangeFeed {

    public static final int DEFAULT_CAPACITY = 512;
    public static final long DEFAULT_WINDOW_MS = 10 * 60 * 1000L;

    private final int capacity;
    private final long windowMs;
    private final LongSupplier nowMs;
    private final String epoch;
    private final Deque<ChangeEvent> entries = new ArrayDeque<>();
    private long nextSeq = 1L;

    public InMemoryChangeFeed(String epoch) {
        this(epoch, DEFAULT_CAPACITY, DEFAULT_WINDOW_MS, System::currentTimeMillis);
    }

    public InMemoryChangeFeed(String epoch, int capacity, long windowMs, LongSupplier nowMs) {
        this.epoch = epoch == null || epoch.isBlank() ? "epoch-0" : epoch;
        this.capacity = Math.max(1, capacity);
        this.windowMs = Math.max(1L, windowMs);
        this.nowMs = nowMs == null ? System::currentTimeMillis : nowMs;
    }

    @Override
    public synchronized long append(String topic, String key, ChangeEvent.Kind kind,
                                    long observationRevision, long epochMs) {
        evictExpired();
        long seq = nextSeq++;
        entries.addLast(new ChangeEvent(seq, topic, key, kind, observationRevision,
                epochMs == 0 ? nowMs.getAsLong() : epochMs));
        while (entries.size() > capacity) entries.removeFirst();
        return seq;
    }

    @Override
    public synchronized ChangesPage query(long cursor, String epochHint, Set<String> topics, int limit) {
        evictExpired();
        // 不知道 epoch 就不能信游标：null/空一律按 reset（审计实测过：放行 null 会让
        // 重启后的消费者把新序号当成旧流的延续，静默错答比 reset 糟得多）。
        boolean epochMismatch = epochHint == null || epochHint.isBlank() || !epochHint.equals(epoch);
        if (cursor < 0) cursor = 0;
        long watermark = watermark();
        if (epochMismatch) {
            return new ChangesPage(List.of(), watermark, true, watermark, false, epoch);
        }
        if (entries.isEmpty()) {
            // 正游标 + 空日志：历史不可用 → reset（不是"没变化"）
            boolean reset = cursor > 0;
            return new ChangesPage(List.of(), cursor, reset, watermark, false, epoch);
        }
        long oldest = entries.peekFirst().streamSeq();
        // 注意不能用 cursor>0 做前提：cursor=0 表示"从没读过"，此时如果 seq 1 已被淘汰，
        // 中间那段同样丢了，必须 reset（这条是测试逼出来的，042 R3-1 的同类错误）。
        if (cursor < oldest - 1) {
            // 中间那段已被淘汰
            return new ChangesPage(List.of(), watermark, true, watermark, entries.size() > 0, epoch);
        }
        if (cursor > watermark) {
            return new ChangesPage(List.of(), watermark, true, watermark, false, epoch);
        }
        int max = limit <= 0 ? Integer.MAX_VALUE : limit;
        List<ChangeEvent> out = new ArrayList<>();
        long lastScanned = cursor;
        for (ChangeEvent e : entries) {
            if (e.streamSeq() <= cursor) continue;
            lastScanned = e.streamSeq();                 // 即使被 topic 过滤，也算"检查过"
            if (topics != null && !topics.isEmpty() && !topics.contains(e.topic())) continue;
            out.add(e);
            if (out.size() >= max) break;
        }
        boolean hasMore = lastScanned < watermark;
        return new ChangesPage(out, lastScanned, false, watermark, hasMore, epoch);
    }

    @Override
    public synchronized long watermark() {
        return entries.isEmpty() ? 0L : entries.peekLast().streamSeq();
    }

    @Override
    public synchronized String streamEpoch() {
        return epoch;
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
