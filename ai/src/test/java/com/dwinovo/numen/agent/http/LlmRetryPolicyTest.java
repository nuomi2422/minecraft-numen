package com.dwinovo.numen.agent.http;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LlmRetryPolicyTest {
    @Test void first429BacksOffThenSucceeds() {
        var d = LlmRetryPolicy.decide(0, 0, 0.5);
        assertTrue(d.retry());
        assertEquals(2_000L, LlmRetryPolicy.baseDelayMs(0));
    }
    @Test void delaysGrowToCap() {
        assertEquals(4_000L, LlmRetryPolicy.baseDelayMs(1));
        assertEquals(8_000L, LlmRetryPolicy.baseDelayMs(2));
        assertEquals(16_000L, LlmRetryPolicy.baseDelayMs(3));
        assertEquals(60_000L, LlmRetryPolicy.baseDelayMs(20));
    }
    @Test void consecutive429ExhaustsAtFiveMinutesOrAttemptCap() {
        var late = LlmRetryPolicy.decide(5, 299_000L, 0.5);
        assertFalse(late.retry());
        assertTrue(late.exhausted());
        assertTrue(LlmRetryPolicy.decide(LlmRetryPolicy.MAX_ATTEMPTS, 0, 0.5).exhausted());
    }
    @Test void ioFailureUsesSamePolicy() {
        var d = LlmRetryPolicy.decide(1, 30_000, 0.5);
        assertTrue(d.retry());
        assertEquals(4_000L, d.delayMs());
    }
}
