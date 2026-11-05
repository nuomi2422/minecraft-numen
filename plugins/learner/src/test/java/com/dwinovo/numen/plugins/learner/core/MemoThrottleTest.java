package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MemoThrottleTest {

    @BeforeEach
    void clear() { MemoThrottle.reset(); }

    @Test
    void allowsUpToTheLimitThenRefuses() {
        UUID id = UUID.randomUUID();
        for (int i = 0; i < MemoThrottle.MAX_PER_DAY; i++) {
            assertTrue(MemoThrottle.allow(id, 100L), "第 " + (i + 1) + " 条应当允许");
        }
        assertFalse(MemoThrottle.allow(id, 100L), "超过上限必须拒收");
    }

    @Test
    void newDayResetsTheCount() {
        UUID id = UUID.randomUUID();
        for (int i = 0; i < MemoThrottle.MAX_PER_DAY; i++) MemoThrottle.allow(id, 100L);
        assertFalse(MemoThrottle.allow(id, 100L));
        assertTrue(MemoThrottle.allow(id, 101L), "换一天应重新计数");
    }

    @Test
    void differentCompanionsDoNotShareTheCount() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        for (int i = 0; i < MemoThrottle.MAX_PER_DAY; i++) MemoThrottle.allow(a, 5L);
        assertTrue(MemoThrottle.allow(b, 5L), "别人写满不影响另一个人");
    }
}
