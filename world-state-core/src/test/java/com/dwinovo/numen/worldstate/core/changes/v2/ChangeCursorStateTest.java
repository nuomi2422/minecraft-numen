package com.dwinovo.numen.worldstate.core.changes.v2;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 041 §18.2 / 042 R3-5：消费者侧 RESET 退避与活锁熔断（离线、纯逻辑）。 */
class ChangeCursorStateTest {

    private static ChangesPage page(List<ChangeEvent> events, long next, boolean reset,
                                    long watermark, String epoch) {
        return new ChangesPage(events, next, reset, watermark, false, epoch);
    }

    private static ChangeEvent ev(long seq) {
        return new ChangeEvent(seq, "containers", "k" + seq, ChangeEvent.Kind.UPDATED, seq, 1L);
    }

    @Test
    void normalPageAdvancesCursor() {
        ChangeCursorState s = new ChangeCursorState();
        boolean reset = s.onPage(page(List.of(ev(1), ev(2)), 2, false, 5, "e1"));
        assertFalse(reset);
        assertEquals(2L, s.cursor());
        assertEquals(0, s.consecutiveResets());
        assertEquals(0L, s.backoffMs());
        assertFalse(s.livelocked());
    }

    @Test
    void emptyPageDoesNotCountAsFailure() {
        ChangeCursorState s = new ChangeCursorState();
        s.onPage(page(List.of(), 7, false, 7, "e1"));
        assertEquals(7L, s.cursor());
        assertEquals(0, s.consecutiveResets(), "没变化不是失败，不该累计退避");
    }

    @Test
    void resetJumpsToWatermarkAndBacksOff() {
        ChangeCursorState s = new ChangeCursorState();
        assertTrue(s.onPage(page(List.of(), 0, true, 42, "e1")));
        assertEquals(42L, s.cursor(), "reset 后游标跟到 watermark，避免原地打转");
        assertEquals(1, s.consecutiveResets());
        assertEquals(1_000L, s.backoffMs());
        long second = s.onResyncFailed();
        assertEquals(2_000L, second, "退避翻倍");
    }

    @Test
    void threeConsecutiveResetsTripLivelock() {
        ChangeCursorState s = new ChangeCursorState(3);
        s.onPage(page(List.of(), 0, true, 10, "e1"));
        s.onPage(page(List.of(), 0, true, 11, "e1"));
        assertFalse(s.livelocked(), "两次还不算活锁");
        s.onPage(page(List.of(), 0, true, 12, "e1"));
        assertTrue(s.livelocked(), "连续三次 RESET 必须熔断，转有界轮询");
    }

    @Test
    void successClearsLivelock() {
        ChangeCursorState s = new ChangeCursorState(2);
        s.onPage(page(List.of(), 0, true, 1, "e1"));
        s.onPage(page(List.of(), 0, true, 2, "e1"));
        assertTrue(s.livelocked());
        s.onPage(page(List.of(ev(3)), 3, false, 3, "e1"));
        assertFalse(s.livelocked(), "读到真变化 = 链路恢复，熔断解除");
        assertEquals(0, s.consecutiveResets());
        assertEquals(0L, s.backoffMs());
    }

    @Test
    void epochChangeTriggersResyncButIsNotAFailure() {
        ChangeCursorState s = new ChangeCursorState(2);
        s.onPage(page(List.of(ev(1)), 1, false, 1, "e1"));
        boolean reset = s.onPage(page(List.of(ev(9)), 9, false, 9, "e2"));
        assertTrue(reset, "epoch 变化必须重同步");
        assertEquals(9L, s.cursor());
        assertEquals("e2", s.epoch());
        assertEquals(0, s.consecutiveResets(), "换流是正常事件，不该计入失败");
        assertEquals(0L, s.backoffMs(), "换流应该立刻重同步，不该退避");
        assertFalse(s.livelocked());
    }

    @Test
    void repeatedNormalRestartsNeverTripLivelock() {
        // 审计实测：一天重启十几次是常态，旧实现三次就误判活锁
        ChangeCursorState s = new ChangeCursorState(3);
        for (int i = 1; i <= 8; i++) {
            s.onPage(page(List.of(ev(i)), i, false, i, "epoch-" + i));
        }
        assertFalse(s.livelocked(), "正常重启不能触发熔断");
        assertEquals(0, s.consecutiveResets());
        assertEquals(0L, s.backoffMs());
    }

    @Test
    void backoffIsCapped() {
        ChangeCursorState s = new ChangeCursorState();
        long last = 0L;
        for (int i = 0; i < 12; i++) last = s.onResyncFailed();
        assertEquals(ChangeCursorState.MAX_BACKOFF_MS, last, "退避必须有 30 秒上限");
    }

    @Test
    void manualClearUnlocks() {
        ChangeCursorState s = new ChangeCursorState(1);
        s.onPage(page(List.of(), 0, true, 1, "e1"));
        assertTrue(s.livelocked());
        s.clearLivelock();
        assertFalse(s.livelocked());
        assertEquals(0, s.consecutiveResets());
    }

    @Test
    void nullPageIsIgnored() {
        ChangeCursorState s = new ChangeCursorState();
        assertFalse(s.onPage(null));
        assertEquals(0L, s.cursor());
    }

    @Test
    void pageWithMoreButNoEventsStillAdvances() {
        ChangeCursorState s = new ChangeCursorState();
        ChangesPage p = new ChangesPage(List.of(), 5, false, 5, true, "e1");
        s.onPage(p);
        assertEquals(5L, s.cursor(), "被过滤掉的页也要前进，否则永远卡在同一处");
    }

    @Test
    void mapShapeUnused() {
        // 防呆：ChangesPage 的构造参数顺序容易被写反，这里用命名构造再断言一次
        ChangesPage p = new ChangesPage(List.of(), 3, false, 3, false, "e-ok");
        assertEquals("e-ok", p.streamEpoch());
        assertEquals(3L, p.watermark());
        assertTrue(Map.of().isEmpty());
    }
}
