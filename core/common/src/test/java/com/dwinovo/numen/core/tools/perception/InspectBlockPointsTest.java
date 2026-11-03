package com.dwinovo.numen.core.tools.perception;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

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

    /** 批量形态是**加法**：x/y/z 变成可选、新增 points，老调用（只传 x/y/z）语义不变。 */
    @Test void schemaOffersPointsWithoutBreakingSingleBlockForm() {
        Map<String, Object> schema = new InspectBlockTool().parameterSchema();
        Object props = schema.get("properties");
        assertTrue(props instanceof Map, "schema 必须有 properties：" + schema);
        Map<?, ?> p = (Map<?, ?>) props;
        for (String k : new String[]{"x", "y", "z", "points"}) {
            assertTrue(p.containsKey(k), "缺字段 " + k + "：" + p.keySet());
        }
        Object required = schema.get("required");
        if (required instanceof java.util.Collection<?> req) {
            assertFalse(req.contains("x"), "x 不能再是必填（否则 points 单独用不了）");
            assertFalse(req.contains("points"), "points 必须是可选");
        }
        assertTrue(new InspectBlockTool().description().contains("points"), "描述要告诉模型有批量形态");
    }

    @Test void rejectsWrongArityOrGarbage() {
        assertNull(InspectBlockTool.parsePoint("1,2"));
        assertNull(InspectBlockTool.parsePoint("1,2,3,4"));
        assertNull(InspectBlockTool.parsePoint("a,b,c"));
        assertNull(InspectBlockTool.parsePoint(null));
    }
}
