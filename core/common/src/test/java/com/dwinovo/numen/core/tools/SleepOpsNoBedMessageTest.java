package com.dwinovo.numen.core.tools;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-05：noBed 回执的纯字符串拼装。
 *
 * <p>来源是一次审计指出的三个问题：非坐标分支多了一个前导空格、扫描半径过大、没有测试。
 * 前两个已改；这里补测试，把「说什么」钉住——行为效果（它会不会走过去）不在此列，只能现场看。
 */
class SleepOpsNoBedMessageTest {

    @Test
    void coordsGivenSaysThereIsNoBedThere() {
        String msg = SleepOps.noBedMessage(true, null, "357,65,671");
        assertTrue(msg.startsWith("there is no bed at those coordinates."), "实际：" + msg);
        assertTrue(msg.contains("The nearest bed is at 357,65,671"), "要给出坐标：" + msg);
    }

    @Test
    void noLeadingSpaceOnTheNoCoordsBranch() {
        String msg = SleepOps.noBedMessage(false, null, null);
        assertFalse(msg.contains("  "), "不许出现连续空格/前导空格：" + msg);
        assertTrue(msg.startsWith("there is no bed within reach"), "实际：" + msg);
    }

    @Test
    void carriedBedBeatsCoordinates() {
        String msg = SleepOps.noBedMessage(true, "one white bed", "1,2,3");
        assertTrue(msg.contains("You are carrying one white bed"), "手里有床先让她放：" + msg);
        assertFalse(msg.contains("The nearest bed is at"), "有床时不要再指远处：" + msg);
    }

    @Test
    void nothingFoundFallsBackToScan() {
        String msg = SleepOps.noBedMessage(true, null, null);
        assertEquals("there is no bed at those coordinates. Use scan_blocks with #minecraft:beds to find one, goto it, then call sleep again.", msg);
    }
}
