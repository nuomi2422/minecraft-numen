package com.dwinovo.numen.agent.http;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07: visible throttle/unavailable flow (policy-level; transport wiring compiled separately). */
class LlmThrottleFlowTest {
    private static List<String> run(boolean everSucceeds, int failures) {
        List<String> events = new ArrayList<>();
        long elapsed = 0;
        int attempt = 0;
        while (attempt < failures) {
            var d = LlmRetryPolicy.decide(attempt, elapsed, 0.5);
            if (!d.retry()) { events.add("llm_unavailable"); return events; }
            events.add("llm_throttled@" + attempt);
            elapsed += d.delayMs();
            attempt++;
        }
        if (everSucceeds) events.add("success");
        return events;
    }
    @Test void one429RetriesThenSucceeds() {
        var e = run(true, 1);
        assertEquals(List.of("llm_throttled@0", "success"), e);
    }
    @Test void consecutive429EndsWithUnavailable() {
        var e = run(false, 50);
        assertEquals("llm_unavailable", e.get(e.size()-1));
        assertTrue(e.size() > 3);
    }
    @Test void ioFailureFollowsSamePolicy() {
        var e = run(true, 1);
        assertTrue(e.contains("llm_throttled@0"));
    }
}
