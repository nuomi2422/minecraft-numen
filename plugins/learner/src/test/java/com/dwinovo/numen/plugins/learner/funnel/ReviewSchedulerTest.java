package com.dwinovo.numen.plugins.learner.funnel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 复盘调度：五种触发 + 空队列不唤起（158 §8）。
 */
class ReviewSchedulerTest {

    private static final long NOW = 1_700_000_000_000L;
    private static final long TIMEOUT = 10 * 60 * 1000L;

    private static ReviewScheduler.Queue q(int pending, int high, long oldest, boolean safePause, boolean ended) {
        return new ReviewScheduler.Queue(pending, high, oldest, safePause, ended);
    }

    @Test
    void 空队列绝不唤起() {
        assertEquals(ReviewScheduler.Trigger.NONE,
                ReviewScheduler.due(q(0, 0, NOW - TIMEOUT * 5, true, true), NOW, 1, TIMEOUT),
                "空复盘会真的调一次模型：成本比多等一分钟高得多");
        assertEquals(ReviewScheduler.Trigger.NONE,
                ReviewScheduler.due(null, NOW, 1, TIMEOUT));
        assertEquals(ReviewScheduler.Trigger.NONE,
                ReviewScheduler.due(ReviewScheduler.Queue.empty(), NOW, 1, TIMEOUT));
    }

    @Test
    void 运行结束最优先() {
        assertEquals(ReviewScheduler.Trigger.RUN_END,
                ReviewScheduler.due(q(5, 3, NOW, true, true), NOW, 1, TIMEOUT),
                "再不做这批样本就随存档沉底了");
    }

    @Test
    void 高优先级先于数量与超时() {
        assertEquals(ReviewScheduler.Trigger.HIGH_PRIORITY,
                ReviewScheduler.due(q(9, 1, NOW - TIMEOUT * 5, true, false), NOW, 3, TIMEOUT));
    }

    @Test
    void 数量阈值触发() {
        assertEquals(ReviewScheduler.Trigger.COUNT,
                ReviewScheduler.due(q(3, 0, NOW, false, false), NOW, 3, TIMEOUT));
        assertEquals(ReviewScheduler.Trigger.NONE,
                ReviewScheduler.due(q(2, 0, NOW, false, false), NOW, 3, TIMEOUT));
    }

    @Test
    void 超时触发且边界是含等号() {
        assertEquals(ReviewScheduler.Trigger.TIMEOUT,
                ReviewScheduler.due(q(1, 0, NOW - TIMEOUT, false, false), NOW, 99, TIMEOUT),
                "刚好等满也算超时");
        assertEquals(ReviewScheduler.Trigger.NONE,
                ReviewScheduler.due(q(1, 0, NOW - TIMEOUT + 1, false, false), NOW, 99, TIMEOUT));
    }

    @Test
    void 最老时间未知时不算超时() {
        assertEquals(ReviewScheduler.Trigger.NONE,
                ReviewScheduler.due(q(1, 0, 0L, false, false), NOW, 99, TIMEOUT),
                "不知道多老就不能假装超时");
    }

    @Test
    void 安全停顿是最后手段() {
        assertEquals(ReviewScheduler.Trigger.SAFE_PAUSE,
                ReviewScheduler.due(q(1, 0, NOW, true, false), NOW, 99, TIMEOUT));
    }

    @Test
    void 阈值配成0视为不启用() {
        assertEquals(ReviewScheduler.Trigger.NONE,
                ReviewScheduler.due(q(100, 0, NOW, false, false), NOW, 0, 0));
    }
}
