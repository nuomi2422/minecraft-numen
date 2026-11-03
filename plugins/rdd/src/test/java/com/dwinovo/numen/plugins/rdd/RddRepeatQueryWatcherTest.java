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

    /**
     * 2026-11-03（外部学习者 P0）：逐点扫描工具的提醒不能再说「答案没变」。
     *
     * <p>实机 13:00–13:28：{@code inspect_block} 56 次、46 个不同坐标。watcher 按
     * {@code tool + args_chars} 计数把它们当成同一次查询，发出的「答案没变」是错的，
     * 模型当然不理。逐点工具的提醒必须指向能一次扫一片的 {@code scan_blocks}。
     */
    @Test
    void pointScanToolIsToldToUseAreaScanInsteadOfRepeatWarning() {
        String text = RddRepeatQueryWatcher.nudgeText("inspect_block", 3);
        assertTrue(text.contains("scan_blocks"), "必须指向面扫描工具，否则模型没有替代动作：" + text);
        assertTrue(text.contains("逐点"), "必须点明这是逐点枚举：" + text);
        assertFalse(text.contains("答案没变"), "逐点查的是不同方块，答案本来就不同，不能这么说：" + text);
    }

    @Test
    void ordinaryToolKeepsTheOriginalRepeatWarning() {
        String text = RddRepeatQueryWatcher.nudgeText("rdd_get_inventory", 3);
        assertTrue(text.contains("答案没变"), "非逐点工具沿用原文案：" + text);
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
