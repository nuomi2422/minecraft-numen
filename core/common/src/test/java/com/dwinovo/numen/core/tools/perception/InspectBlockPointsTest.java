package com.dwinovo.numen.core.tools.perception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * P0（2026-11-03，外部学习者）：{@code inspect_block} 的批量形态 {@code points} 解析。
 *
 * <p>为什么要有批量形态：实机 13:00–13:28 为找一个放床的位置逐点调了 56 次 inspect_block，
 * 每格一次完整往返（约 38k 输入 token/轮）—— 56 轮 ≈ 215 万 token。批量入口把这段压成 1 次调用。
 */
class InspectBlockPointsTest {

    @Test void parsesPlainTriple() {
        assertArrayEquals(new int[]{-364, 82, 772}, InspectBlockTool.parsePoint("-364,82,772"));
    }

    @Test void toleratesSpaces() {
        assertArrayEquals(new int[]{1, 2, 3}, InspectBlockTool.parsePoint(" 1 , 2 , 3 "));
    }

    @Test void rejectsWrongArityOrGarbage() {
        assertNull(InspectBlockTool.parsePoint("1,2"));
        assertNull(InspectBlockTool.parsePoint("1,2,3,4"));
        assertNull(InspectBlockTool.parsePoint("a,b,c"));
        assertNull(InspectBlockTool.parsePoint(null));
    }
}
