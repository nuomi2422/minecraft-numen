package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 空转提醒的护栏：**不该说话时一个字都不说**，该说话时必须给出对症动作。
 */
class RddStuckWatchTest {

    private final UUID id = UUID.randomUUID();

    @Test
    void 换步骤就重置_不算空转() {
        RddStuckWatch w = new RddStuckWatch();
        long t0 = 1_000_000L;
        w.tick(id, "sub-A", 100, t0, "深入地下找岩浆");
        // 6 分钟后换了步骤 → 重置，不该提示
        String s = w.tick(id, "sub-B", 100, t0 + 6 * 60_000L, "深入地下找岩浆");
        assertEquals("", s);
        // 再等 2 分钟（新步骤才 2 分钟）→ 仍不提示
        assertEquals("", w.tick(id, "sub-B", 100, t0 + 8 * 60_000L, "深入地下找岩浆"));
    }

    @Test
    void 同一二级久跑且资产不变才提示() {
        RddStuckWatch w = new RddStuckWatch();
        long t0 = 1_000_000L;
        w.tick(id, "sub-A", 100, t0, "深入地下找岩浆");
        assertEquals("", w.tick(id, "sub-A", 100, t0 + 60_000L, "深入地下找岩浆"), "1 分钟不提示");
        String s = w.tick(id, "sub-A", 100, t0 + 6 * 60_000L, "深入地下找岩浆");
        assertTrue(s.contains("<stuck_hint>"), "6 分钟没进展要提示");
        assertTrue(s.contains("空转"));
        assertTrue(s.contains("goto") || s.contains("挖"), "必须给出具体动作");
    }

    @Test
    void 资产在涨就不算空转() {
        RddStuckWatch w = new RddStuckWatch();
        long t0 = 1_000_000L;
        w.tick(id, "sub-A", 100, t0, "采集黑曜石");
        String s = w.tick(id, "sub-A", 130, t0 + 6 * 60_000L, "采集黑曜石");
        assertEquals("", s, "背包在变多说明在干活，不要打扰");
    }

    @Test
    void 没有二级时不提示也不炸() {
        RddStuckWatch w = new RddStuckWatch();
        assertEquals("", w.tick(id, "", 100, 1_000_000L, "x"));
        assertEquals("", w.tick(null, "sub-A", 100, 1_000_000L, "x"));
        w.clear(id);
    }

    @Test
    void 文本块有界() {
        String s = RddStuckText.block(20, 999, true, "深入地下 Y≤30 找岩浆区域");
        assertTrue(s.length() < 700, "上下文块必须有界，实际 " + s.length());
    }
}
