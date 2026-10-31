package com.dwinovo.numen.plugins.learner.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NEEDS_EXECUTOR_INPUT 票据：VERIFIED / UNVERIFIED / EXPIRED 三态必须分得开。
 */
class ExecutorInputTicketTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long DEADLINE = T0 + 60_000L;

    private static ExecutorInputTicket open() {
        return ExecutorInputTicket.open(new ExecutorInputTicket.Request(
                "req-1", "s-1", "你当时看到岩浆了吗？", java.util.List.of("position"), T0, DEADLINE));
    }

    private static ExecutorInputTicket.Answer answer(long at, EvidenceRef... refs) {
        return new ExecutorInputTicket.Answer("req-1", "看到了，左边三格",
                java.util.List.of(refs), at);
    }

    @Test
    void 引用决策上下文块才是VERIFIED() {
        ExecutorInputTicket t = open();
        var r = t.answer(answer(T0 + 1_000, EvidenceRef.parse("block:dc-turn-3")),
                id -> "dc-turn-3".equals(id)
                        ? java.util.Optional.of(TestBlocks.decisionContext("dc-turn-3"))
                        : java.util.Optional.empty());

        assertTrue(r.accepted());
        assertEquals(ExecutorInputTicket.State.VERIFIED, r.state());
    }

    @Test
    void 引用样本摘要块只能算UNVERIFIED() {
        ExecutorInputTicket t = open();
        var r = t.answer(answer(T0 + 1_000, EvidenceRef.parse("block:summary-s-1")),
                id -> java.util.Optional.of(TestBlocks.summary("summary-s-1")));

        assertTrue(r.accepted(), "话是收到了，但追不到当时的上下文");
        assertEquals(ExecutorInputTicket.State.UNVERIFIED, r.state());
        assertEquals("no_decision_context_ref", r.note());
    }

    @Test
    void 没有证据也算UNVERIFIED并注明() {
        ExecutorInputTicket t = open();
        var r = t.answer(answer(T0 + 1_000), id -> java.util.Optional.empty());
        assertEquals(ExecutorInputTicket.State.UNVERIFIED, r.state());
        assertEquals("no_evidence", r.note());
    }

    @Test
    void 超时后收下但不采信() {
        ExecutorInputTicket t = open();
        var r = t.answer(answer(DEADLINE + 1, EvidenceRef.parse("block:dc-turn-3")),
                id -> java.util.Optional.of(TestBlocks.decisionContext(id)));

        assertFalse(r.accepted(), "过期补上来的「我当时看到」无法与当时的环境对齐");
        assertEquals(ExecutorInputTicket.State.EXPIRED, r.state());
        assertTrue(t.answer().isEmpty(), "过期的答案不入票");
    }

    @Test
    void 不许重复作答() {
        ExecutorInputTicket t = open();
        t.answer(answer(T0 + 1, EvidenceRef.parse("block:dc-1")),
                id -> java.util.Optional.of(TestBlocks.decisionContext(id)));
        var again = t.answer(answer(T0 + 2, EvidenceRef.parse("block:dc-1")),
                id -> java.util.Optional.of(TestBlocks.decisionContext(id)));

        assertFalse(again.accepted());
        assertEquals("ticket_not_open", again.note());
    }

    @Test
    void requestId对不上直接拒() {
        ExecutorInputTicket t = open();
        var r = t.answer(new ExecutorInputTicket.Answer("other", "x",
                java.util.List.of(), T0 + 1), id -> java.util.Optional.empty());
        assertFalse(r.accepted());
        assertEquals("request_id_mismatch", r.note());
    }

    @Test
    void 空答案是拒不是UNVERIFIED() {
        ExecutorInputTicket t = open();
        var r = t.answer(new ExecutorInputTicket.Answer("req-1", "  ", java.util.List.of(), T0 + 1),
                id -> java.util.Optional.empty());
        assertFalse(r.accepted());
        assertEquals("empty_answer", r.note());
        assertEquals(ExecutorInputTicket.State.OPEN, t.state(), "拒掉之后票据还开着");
    }

    @Test
    void 关闭后不再收答() {
        ExecutorInputTicket t = open();
        assertTrue(t.close("sample archived").accepted());
        var r = t.answer(answer(T0 + 1), id -> java.util.Optional.empty());
        assertFalse(r.accepted());
        assertEquals(ExecutorInputTicket.State.CLOSED, t.state());
    }

    @Test
    void 截止时间必须晚于发出时间() {
        assertThrows(IllegalArgumentException.class, () -> new ExecutorInputTicket.Request(
                "r", "s", "q", java.util.List.of(), T0, T0));
        assertThrows(IllegalArgumentException.class, () -> new ExecutorInputTicket.Request(
                "r", "", "q", java.util.List.of(), T0, DEADLINE));
        assertThrows(IllegalArgumentException.class, () -> new ExecutorInputTicket.Request(
                "r", "s", " ", java.util.List.of(), T0, DEADLINE));
    }
}
