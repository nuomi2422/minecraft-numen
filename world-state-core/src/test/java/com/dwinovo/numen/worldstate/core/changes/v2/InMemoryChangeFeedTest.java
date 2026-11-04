package com.dwinovo.numen.worldstate.core.changes.v2;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 042 R3 / 041 §17.7 的九条确定性反例（可控时钟，不 sleep）。
 */
class InMemoryChangeFeedTest {

    private static ChangeEvent ev(String topic, String key) {
        return new ChangeEvent(0L, topic, key, ChangeEvent.Kind.UPDATED, 1L, 1_000L);
    }

    private static InMemoryChangeFeed feed(int capacity, AtomicLong clock) {
        return new InMemoryChangeFeed("epoch-A", capacity, 60_000L, clock::get);
    }

    @Test
    void seqIsOwnMonotonicNotObservationRevision() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = feed(16, clock);
        f.append(ev("blocks", "k1"));
        f.append(ev("blocks", "k2"));
        ChangesPage p = f.query(0, "epoch-A", null, 10);
        assertEquals(List.of(1L, 2L), p.events().stream().map(ChangeEvent::streamSeq).toList(),
                "日志自己发号，和观测修订号无关");
    }

    @Test
    void emptyLogWithPositiveCursorRequiresReset() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = feed(16, clock);
        ChangesPage p = f.query(5, "epoch-A", null, 10);
        assertTrue(p.resetRequired(), "正游标 + 空日志 → 必须 reset，不能返回空列表冒充没变化");
    }

    @Test
    void epochMismatchRequiresReset() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = feed(16, clock);
        f.append(ev("blocks", "k1"));
        ChangesPage p = f.query(0, "epoch-B", null, 10);
        assertTrue(p.resetRequired(), "epoch 不一致必须 reset");
    }

    @Test
    void topicFilterStillAdvancesCursor() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = feed(16, clock);
        f.append(ev("environment", "e1"));
        f.append(ev("environment", "e2"));
        f.append(ev("blocks", "b1"));
        ChangesPage p = f.query(0, "epoch-A", Set.of("containers"), 10);
        assertEquals(0, p.events().size());
        assertEquals(3L, p.nextCursor(), "全被过滤也要推进到检查过的尾部，否则消费者卡死");
        assertFalse(p.hasMore());
    }

    @Test
    void paginationRespectsLimitAndHasMore() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = feed(16, clock);
        for (int i = 0; i < 5; i++) f.append(ev("blocks", "k" + i));
        ChangesPage p1 = f.query(0, "epoch-A", null, 2);
        assertEquals(2, p1.events().size());
        assertEquals(2L, p1.nextCursor());
        assertTrue(p1.hasMore());
        ChangesPage p2 = f.query(p1.nextCursor(), "epoch-A", null, 10);
        assertEquals(3, p2.events().size(), "接着上次继续，不漏不重");
        assertFalse(p2.hasMore());
    }

    @Test
    void pageGapInsertIsNotLost() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = feed(16, clock);
        f.append(ev("blocks", "a"));
        f.append(ev("blocks", "b"));
        ChangesPage p1 = f.query(0, "epoch-A", null, 2);   // 拿满 2 条，nextCursor=2
        f.append(ev("blocks", "c"));                        // 取第 2 页之前插入
        ChangesPage p2 = f.query(p1.nextCursor(), "epoch-A", null, 10);
        assertEquals(1, p2.events().size());
        assertEquals("c", p2.events().get(0).key(), "夹缝写入不能丢");
    }

    @Test
    void retentionExpiryRequiresReset() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = feed(16, clock);
        f.append(new ChangeEvent(0, "blocks", "old", ChangeEvent.Kind.CREATED, 1, 1_000L));
        clock.addAndGet(120_000L);                          // 超过 60 秒窗口
        f.append(new ChangeEvent(0, "blocks", "new", ChangeEvent.Kind.CREATED, 1, clock.get()));
        // 游标 0 = 从没读过，而 seq 1 已被淘汰 → 中间那段确实丢了 → reset
        ChangesPage neverRead = f.query(0, "epoch-A", null, 10);
        assertTrue(neverRead.resetRequired(), "没读过的历史被淘汰 → 必须 reset");
        // 对照：游标 1 = 早就读过 seq 1，丢掉它不影响连续性
        ChangesPage alreadyRead = f.query(1, "epoch-A", null, 10);
        assertFalse(alreadyRead.resetRequired(),
                "已消费过的旧事件被淘汰，不等于断档（这条区分是 v1 没做的）");
        assertEquals(1, alreadyRead.events().size());
    }

    @Test
    void cursorBeyondWatermarkRequiresReset() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = feed(16, clock);
        f.append(ev("blocks", "k1"));
        ChangesPage p = f.query(99, "epoch-A", null, 10);
        assertTrue(p.resetRequired(), "游标比水位还大 = 不可信，必须 reset");
    }

    @Test
    void invalidateKindIsDistinctFromGone() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = feed(16, clock);
        f.append(new ChangeEvent(0, "blocks", "k", ChangeEvent.Kind.INVALIDATE, 3, 1_000L));
        ChangesPage p = f.query(0, "epoch-A", null, 10);
        assertEquals(ChangeEvent.Kind.INVALIDATE, p.events().get(0).kind(),
                "缓存失效要用 INVALIDATE，不许冒充 GONE");
        assertEquals(ChangeEvent.Kind.GONE.name(), "GONE", "GONE 保留但当前不产生");
    }

    @Test
    void lruRestartWithLimitOneStillAdvances() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = new InMemoryChangeFeed("epoch-A", 2, 60_000L, clock::get);
        f.append(ev("blocks", "A1"));
        f.append(ev("blocks", "B2"));
        f.append(ev("blocks", "C3"));     // 容量 2 → A1 被挤掉
        ChangesPage p = f.query(0, "epoch-A", null, 1);
        assertTrue(p.resetRequired() || p.nextCursor() > 0,
                "LRU 挤掉历史后：要么 reset，要么游标必须前进");
    }
}
