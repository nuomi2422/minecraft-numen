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
        f.append("blocks", "k1", ChangeEvent.Kind.UPDATED, 1L, clock.get());
        f.append("blocks", "k2", ChangeEvent.Kind.UPDATED, 1L, clock.get());
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
        f.append("blocks", "k1", ChangeEvent.Kind.UPDATED, 1L, clock.get());
        ChangesPage p = f.query(0, "epoch-B", null, 10);
        assertTrue(p.resetRequired(), "epoch 不一致必须 reset");
    }

    @Test
    void topicFilterStillAdvancesCursor() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = feed(16, clock);
        f.append("environment", "e1", ChangeEvent.Kind.UPDATED, 1L, clock.get());
        f.append("environment", "e2", ChangeEvent.Kind.UPDATED, 1L, clock.get());
        f.append("blocks", "b1", ChangeEvent.Kind.UPDATED, 1L, clock.get());
        ChangesPage p = f.query(0, "epoch-A", Set.of("containers"), 10);
        assertEquals(0, p.events().size());
        assertEquals(3L, p.nextCursor(), "全被过滤也要推进到检查过的尾部，否则消费者卡死");
        assertFalse(p.hasMore());
    }

    @Test
    void paginationRespectsLimitAndHasMore() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = feed(16, clock);
        for (int i = 0; i < 5; i++) f.append("blocks", "k" + i, ChangeEvent.Kind.UPDATED, 1L, clock.get());
        ChangesPage p1 = f.query(0, "epoch-A", null, 2);
        assertEquals(2, p1.events().size());
        assertEquals(2L, p1.nextCursor());
        assertTrue(p1.hasMore());
        ChangesPage p2 = f.query(p1.nextCursor(), "epoch-A", null, 10);
        assertEquals(3, p2.events().size(), "接着上次继续，不漏不重");
        assertFalse(p2.hasMore());
    }

    @Test
    void missingEpochIsTreatedAsReset() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = feed(16, clock);
        for (int i = 0; i < 3; i++) f.append("blocks", "k" + i, ChangeEvent.Kind.UPDATED, 1L, clock.get());
        assertTrue(f.query(2, null, null, 10).resetRequired(),
                "不传 epoch 就不知道游标属于哪条流 → 必须 reset（审计实测过这个洞）");
        assertTrue(f.query(2, "  ", null, 10).resetRequired(), "空白同样按未知处理");
        assertFalse(f.query(2, "epoch-A", null, 10).resetRequired(), "带对的 epoch 才正常续读");
    }

    @Test
    void pageGapInsertIsNotLost() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = feed(16, clock);
        f.append("blocks", "a", ChangeEvent.Kind.UPDATED, 1L, clock.get());
        f.append("blocks", "b", ChangeEvent.Kind.UPDATED, 1L, clock.get());
        ChangesPage p1 = f.query(0, "epoch-A", null, 2);   // 拿满 2 条，nextCursor=2
        f.append("blocks", "c", ChangeEvent.Kind.UPDATED, 1L, clock.get());                        // 取第 2 页之前插入
        ChangesPage p2 = f.query(p1.nextCursor(), "epoch-A", null, 10);
        assertEquals(1, p2.events().size());
        assertEquals("c", p2.events().get(0).key(), "夹缝写入不能丢");
    }

    @Test
    void retentionExpiryRequiresReset() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = feed(16, clock);
        f.append("blocks", "old", ChangeEvent.Kind.CREATED, 1L, 1_000L);
        clock.addAndGet(120_000L);                          // 超过 60 秒窗口
        f.append("blocks", "new", ChangeEvent.Kind.CREATED, 1L, clock.get());
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
        f.append("blocks", "k1", ChangeEvent.Kind.UPDATED, 1L, clock.get());
        ChangesPage p = f.query(99, "epoch-A", null, 10);
        assertTrue(p.resetRequired(), "游标比水位还大 = 不可信，必须 reset");
    }

    @Test
    void invalidateKindIsDistinctFromGone() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = feed(16, clock);
        f.append("blocks", "k", ChangeEvent.Kind.INVALIDATE, 3L, 1_000L);
        ChangesPage p = f.query(0, "epoch-A", null, 10);
        assertEquals(ChangeEvent.Kind.INVALIDATE, p.events().get(0).kind(),
                "缓存失效要用 INVALIDATE，不许冒充 GONE");
        assertEquals(0, p.events().stream().filter(e -> e.kind() == ChangeEvent.Kind.GONE).count(),
                "当前实现不产生 GONE（它表示世界里真的没了）");
    }

    @Test
    void lruRestartWithLimitOneStillAdvances() {
        AtomicLong clock = new AtomicLong(1_000L);
        InMemoryChangeFeed f = new InMemoryChangeFeed("epoch-A", 2, 60_000L, clock::get);
        f.append("blocks", "A1", ChangeEvent.Kind.UPDATED, 1L, clock.get());
        f.append("blocks", "B2", ChangeEvent.Kind.UPDATED, 1L, clock.get());
        f.append("blocks", "C3", ChangeEvent.Kind.UPDATED, 1L, clock.get());     // 容量 2 → A1 被挤掉
        // 容量 2 挤掉 seq=1 之后：从 0 读必须 reset（历史断了）；
        // 而"已经读过 seq=1"的游标 1 接着读则不该 reset。这两条必须分别断言。
        ChangesPage neverRead = f.query(0, "epoch-A", null, 1);
        assertTrue(neverRead.resetRequired(), "LRU 挤掉未读历史 → reset");
        ChangesPage alreadyRead = f.query(1, "epoch-A", null, 1);
        assertFalse(alreadyRead.resetRequired(), "已读过的旧事件被淘汰不算断档");
        assertEquals(1, alreadyRead.events().size());
        assertEquals(2L, alreadyRead.events().get(0).streamSeq());
    }
}