package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 坐标型条件的范围校验（2026-10-28 实机 bug 的回归测试）。
 *
 * <p>实机现象：模型把结构坐标 "(320,848)"（x/z 两维）填成
 * {@code position_at{x:320,y:848,z:848}}，y 超出世界高度 → 条件永远为假 → 整条链 STALLED。
 * 这里钉死：**世界外的坐标必须判无效**，让它走"重新拆步"而不是悄悄永远为假。
 */
class CoordRangeValidationTest {

    private static Map<String, Object> c(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    @Test
    void 正常坐标有效() {
        assertTrue(WorldFactConditions.valid(c("type", "position_at", "x", 100, "y", 64, "z", 200)));
        assertTrue(WorldFactConditions.valid(c("type", "position_at", "x", 0, "y", -60, "z", 0)));
        assertTrue(WorldFactConditions.valid(c("type", "position_at", "x", 0, "y", 320, "z", 0)));
    }

    @Test
    void 超出世界高度的_y_判无效() {
        // 实机抓到的原样：x=320, y=848, z=848
        assertFalse(WorldFactConditions.valid(c("type", "position_at", "x", 320, "y", 848, "z", 848)),
                "y=848 永远不可能成立，必须判无效");
        assertFalse(WorldFactConditions.valid(c("type", "position_at", "x", 0, "y", 321, "z", 0)));
        assertFalse(WorldFactConditions.valid(c("type", "position_at", "x", 0, "y", -65, "z", 0)));
    }

    @Test
    void 水平坐标离谱也判无效() {
        assertFalse(WorldFactConditions.valid(c("type", "position_at", "x", 40_000_000, "y", 64, "z", 0)));
    }

    @Test
    void 缺一个坐标仍然无效() {
        assertFalse(WorldFactConditions.valid(c("type", "position_at", "x", 10, "z", 10)));
    }
}
