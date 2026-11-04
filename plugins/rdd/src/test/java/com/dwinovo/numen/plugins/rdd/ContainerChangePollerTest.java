package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D-020 的离线替身（041 §22.3）：用假数据源断言"有变化 → 重扫"这条分支真的会走。
 *
 * <p>它比 {@code ChangeSignals} 更进一步：这里测的是**轮询器**（带游标状态），
 * 也就是插件里真正被调用的那个对象，只是数据源换成了假的。
 */
class ContainerChangePollerTest {

    @Test
    void changeTriggersRescan() {
        ContainerChangePoller poller = new ContainerChangePoller();
        assertTrue(poller.poll(cursor -> new ContainerChangePoller.Page(false, 7, 2)),
                "有变化就必须重扫（D-020 正触发）");
        assertEquals(7L, poller.cursor(), "游标要跟着往前走");
    }

    @Test
    void resetTriggersRescan() {
        ContainerChangePoller poller = new ContainerChangePoller();
        assertTrue(poller.poll(cursor -> new ContainerChangePoller.Page(true, 9, 0)),
                "断档也要重扫：手里的数据不可信");
    }

    @Test
    void noChangeDoesNotRescan() {
        ContainerChangePoller poller = new ContainerChangePoller();
        assertFalse(poller.poll(cursor -> new ContainerChangePoller.Page(false, 5, 0)));
        assertEquals(5L, poller.cursor());
    }

    @Test
    void unavailableDoesNotRescanAndKeepsCursor() {
        ContainerChangePoller poller = new ContainerChangePoller();
        assertFalse(poller.poll(cursor -> null), "拿不到增量 → 不重扫（退回节奏扫描）");
        assertEquals(0L, poller.cursor(), "拿不到增量时游标不能乱动");
    }

    @Test
    void cursorIsPassedToSource() {
        ContainerChangePoller poller = new ContainerChangePoller();
        AtomicLong seen = new AtomicLong(-1);
        poller.poll(cursor -> {
            seen.set(cursor);
            return new ContainerChangePoller.Page(false, 42, 0);
        });
        poller.poll(cursor -> {
            seen.set(cursor);
            return new ContainerChangePoller.Page(false, 43, 0);
        });
        assertEquals(42L, seen.get(), "第二次轮询必须带上一次的 nextCursor");
    }

    @Test
    void nullSourceIsSafe() {
        ContainerChangePoller poller = new ContainerChangePoller();
        assertFalse(poller.poll(null));
    }
}
