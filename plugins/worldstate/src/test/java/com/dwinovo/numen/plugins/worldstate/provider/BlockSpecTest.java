package com.dwinovo.numen.plugins.worldstate.provider;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 039 U5#8：blocks 规格串解析（纯函数，离线可测）。
 *
 * <p>重点：方括号里的逗号不是条目分隔符；格式坏不静默忽略（否则"按状态过滤"会悄悄退化成按 id）。
 */
class BlockSpecTest {

    @Test
    void plainIds() {
        assertEquals(List.of("minecraft:chest", "minecraft:lava"),
                BlockSpec.parseIds("block=minecraft:chest,minecraft:lava"));
    }

    @Test
    void commasInsideBracketsDoNotSplit() {
        assertEquals(List.of("minecraft:wheat[age=7]", "minecraft:oak_log[axis=y]"),
                BlockSpec.parseIds("block=minecraft:wheat[age=7],minecraft:oak_log[axis=y]"));
    }

    @Test
    void prefixesAndEmptyInput() {
        assertEquals(List.of("minecraft:chest"), BlockSpec.parseIds("id=minecraft:chest"));
        assertTrue(BlockSpec.parseIds("type=minecraft:chest").isEmpty());
        assertTrue(BlockSpec.parseIds("").isEmpty());
        assertTrue(BlockSpec.parseIds(null).isEmpty());
    }

    @Test
    void duplicatesCollapsed() {
        assertEquals(List.of("minecraft:chest"),
                BlockSpec.parseIds("block=minecraft:chest, minecraft:chest"));
    }

    @Test
    void propsParseStrictly() {
        assertEquals(Map.of("age", "7"), BlockSpec.parseProps("age=7"));
        assertEquals(Map.of("axis", "y", "waterlogged", "false"),
                BlockSpec.parseProps("axis=y,waterlogged=false"));
        assertTrue(BlockSpec.parseProps("").isEmpty());
        assertNull(BlockSpec.parseProps("age"), "缺等号=格式坏");
        assertNull(BlockSpec.parseProps("age="), "缺值=格式坏");
        assertNull(BlockSpec.parseProps("=7"), "缺键=格式坏");
    }

    @Test
    void splitSeparatesIdAndProps() {
        String[] a = BlockSpec.split("minecraft:wheat[age=7]");
        assertEquals("minecraft:wheat", a[0]);
        assertEquals("age=7", a[1]);
        String[] b = BlockSpec.split("minecraft:chest");
        assertEquals("minecraft:chest", b[0]);
        assertEquals("", b[1]);
        assertNull(BlockSpec.split("minecraft:wheat[age=7"), "括号不闭合=坏");
    }
}
