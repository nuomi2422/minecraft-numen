package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * H3（108 号）判据①②③：重复查询的运行时可感知。
 *
 * <p>只测纯计数逻辑（不碰 Minecraft 世界、不发 nudge —— nudge 在无同伴时会自己返回 false，
 * 但这里要断言的是「什么时候该提醒」这个判断本身）。
 */
class RddRepeatQueryWatcherTest {

    private static final UUID ID = UUID.fromString("e502d17b-de15-4e5e-9864-9e97b0702546");

    @Test
    void mcpMonitorCallsAreNotCompanionRepeats() {
        assertTrue(RddRepeatQueryWatcher.isExternalCall("mcp-122"), "MCP 采集面应被识别为外部");
        assertTrue(RddRepeatQueryWatcher.isExternalCall("mcp-1"), "任何 mcp- 前缀都算");
        assertFalse(RddRepeatQueryWatcher.isExternalCall("call_00_abc"), "AI 自己的调用不是外部");
        assertFalse(RddRepeatQueryWatcher.isExternalCall(null), "没有 call id 也不判外部");
    }

    @Test
    void firstTwoCallsDoNotNotify() {
        RddRepeatQueryWatcher.clear(ID);
        assertFalse(RddRepeatQueryWatcher.observe(ID, "rdd_get_inventory", 2, 1000L), "① 第 1 次不提醒");
        assertFalse(RddRepeatQueryWatcher.observe(ID, "rdd_get_inventory", 2, 1010L), "① 第 2 次不提醒");
    }

    @Test
    void thirdCallWithinWindowNotifies() {
        RddRepeatQueryWatcher.clear(ID);
        RddRepeatQueryWatcher.observe(ID, "rdd_get_inventory", 2, 2000L);
        RddRepeatQueryWatcher.observe(ID, "rdd_get_inventory", 2, 2010L);
        assertTrue(RddRepeatQueryWatcher.observe(ID, "rdd_get_inventory", 2, 2020L), "② 第 3 次必须提醒");
    }

    @Test
    void differentArgsAreNotRepeats() {
        RddRepeatQueryWatcher.clear(ID);
        RddRepeatQueryWatcher.observe(ID, "scan_blocks", 10, 3000L);
        RddRepeatQueryWatcher.observe(ID, "scan_blocks", 20, 3010L);
        assertFalse(RddRepeatQueryWatcher.observe(ID, "scan_blocks", 30, 3020L), "③ 参数不同不算重复");
    }

    @Test
    void outsideWindowStartsFresh() {
        RddRepeatQueryWatcher.clear(ID);
        RddRepeatQueryWatcher.observe(ID, "rdd_status", 2, 4000L);
        RddRepeatQueryWatcher.observe(ID, "rdd_status", 2, 4010L);
        long farFuture = 4000L + RddRepeatQueryWatcher.WINDOW_TICKS + 1;
        assertFalse(RddRepeatQueryWatcher.observe(ID, "rdd_status", 2, farFuture), "③ 超出窗口重新计数");
    }

    @Test
    void notifyIsThrottled() {
        RddRepeatQueryWatcher.clear(ID);
        RddRepeatQueryWatcher.observe(ID, "rdd_get_inventory", 2, 5000L);
        RddRepeatQueryWatcher.observe(ID, "rdd_get_inventory", 2, 5010L);
        assertTrue(RddRepeatQueryWatcher.observe(ID, "rdd_get_inventory", 2, 5020L), "② 首次提醒");
        assertFalse(RddRepeatQueryWatcher.observe(ID, "rdd_get_inventory", 2, 5030L), "② 节流内不再提醒");
    }
}
