package com.dwinovo.numen.rdd.fail;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Supervisor↔Numen 双向协商协议：ACCEPT/REJECT/COUNTER + 指挥官是否需介入。 */
class TaskNegotiationTest {

    @Test void acceptNeedsNoSupervisorAction() {
        var n = new TaskNegotiation(TaskNegotiation.Kind.ACCEPT, "s1", "", "");
        assertFalse(n.needsSupervisorAction());
        assertTrue(n.render().contains("ACCEPT"));
    }

    @Test void rejectAndCounterNeedAction() {
        var rej = new TaskNegotiation(TaskNegotiation.Kind.REJECT, "s1", "命令与当前目标冲突", "");
        assertTrue(rej.needsSupervisorAction());
        var ctr = new TaskNegotiation(TaskNegotiation.Kind.COUNTER, "s1", "缺铁镐", "先采铁矿做铁镐");
        assertTrue(ctr.needsSupervisorAction());
        assertTrue(ctr.hasSuggestion());
        assertTrue(ctr.render().contains("先采铁矿"));
    }

    @Test void parseIsLenient() {
        assertEquals(TaskNegotiation.Kind.COUNTER, TaskNegotiation.parse("counter", "s", "r", "g").kind());
        assertEquals(TaskNegotiation.Kind.REJECT, TaskNegotiation.parse("REJECT", "s", "r", "").kind());
        assertEquals(TaskNegotiation.Kind.ACCEPT, TaskNegotiation.parse("garbage", "s", "", "").kind());
        assertEquals(TaskNegotiation.Kind.ACCEPT, TaskNegotiation.parse(null, "s", "", "").kind());
    }

    @Test void eventDataIsFlatAndComplete() {
        var n = new TaskNegotiation(TaskNegotiation.Kind.COUNTER, "s1", "做不到", "换方案");
        Map<String, Object> d = n.toEventData("uuid-1");
        assertEquals("uuid-1", d.get("companionId"));
        assertEquals("COUNTER", d.get("kind"));
        assertEquals(true, d.get("needsSupervisorAction"));
        assertNotNull(d.get("summary"));
    }

    @Test void nullsAreTolerated() {
        var n = new TaskNegotiation(TaskNegotiation.Kind.COUNTER, null, null, null);
        assertEquals("", n.taskId());
        assertEquals("", n.reason());
        assertEquals("", n.suggestion());
        assertFalse(n.hasSuggestion());
    }

    @Test void kindsListed() {
        assertEquals(3, TaskNegotiation.kinds().size());
        assertTrue(TaskNegotiation.kinds().contains("COUNTER"));
    }
}
