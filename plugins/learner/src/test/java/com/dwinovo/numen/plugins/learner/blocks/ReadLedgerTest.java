package com.dwinovo.numen.plugins.learner.blocks;

import com.dwinovo.numen.plugins.learner.sample.SampleEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 读取账本：有界、可过滤、调用方标记不许漂移。
 */
class ReadLedgerTest {

    @Test
    void 有界且诚实计数(@org.junit.jupiter.api.io.TempDir java.nio.file.Path ignored) {
        ReadLedger l = new ReadLedger();
        for (int i = 0; i < ReadLedger.MAX_ENTRIES + 10; i++) {
            l.record(ReadLedger.CALLER_LEARNER, ReadLedger.Action.BLOCK_EXPAND, "b" + i, i, "");
        }
        assertEquals(ReadLedger.MAX_ENTRIES, l.entries().size(), "长会话不许把账本撑爆");
        assertEquals(10, l.dropped(), "丢了要如实计数，不许假装还在");
    }

    @Test
    void 可按动作过滤() {
        ReadLedger l = new ReadLedger();
        l.record(ReadLedger.CALLER_LEARNER, ReadLedger.Action.BLOCK_EXPAND, "a", 1, "");
        l.record(ReadLedger.CALLER_LEARNER, ReadLedger.Action.BLOCK_SKIP, "b", 2, "");
        l.record(ReadLedger.CALLER_LEARNER, ReadLedger.Action.BLOCK_EXPAND, "c", 3, "");

        assertEquals(2, l.countOf(ReadLedger.Action.BLOCK_EXPAND));
        assertEquals(1, l.entriesOf(ReadLedger.Action.BLOCK_SKIP).size());
        assertEquals("b", l.entriesOf(ReadLedger.Action.BLOCK_SKIP).get(0).blockId());
    }

    @Test
    void 空调用方记为UNKNOWN而不是空串() {
        ReadLedger l = new ReadLedger();
        l.record(" ", ReadLedger.Action.BLOCK_LIST, "", 1, "");
        assertEquals("UNKNOWN", l.entries().get(0).caller());
    }

    @Test
    void 调用方标记必须与事件层一致() {
        // 两处各写一份是为了解耦，但值必须相同 —— 否则"学习者"在两个账本里是两个人。
        assertEquals(SampleEvent.ACTOR_INTERNAL_LEARNER, ReadLedger.CALLER_LEARNER);
    }

    @Test
    void 清空后计数归零() {
        ReadLedger l = new ReadLedger();
        l.record(ReadLedger.CALLER_LEARNER, ReadLedger.Action.BLOCK_LIST, "", 1, "");
        l.clear();
        assertTrue(l.entries().isEmpty());
        assertEquals(0, l.dropped());
    }
}
