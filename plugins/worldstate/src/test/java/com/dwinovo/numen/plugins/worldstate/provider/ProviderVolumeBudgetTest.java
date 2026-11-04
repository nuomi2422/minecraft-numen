package com.dwinovo.numen.plugins.worldstate.provider;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-05 两次实机事故：调用方用**球形半径**拼盒子，33³ / 49³ 这些常见值都被
 * "按逐格成本定"的 MAX_VOLUME 拒掉（先 32768 拒 33³，后 262144 拒 ... 也差点）。
 *
 * <p>这条测试把"常见半径 → 盒子体积"钉住，防止以后再把上限调回小值。
 */
class ProviderVolumeBudgetTest {

    private static long cube(int radius) {
        long side = 2L * radius + 1;
        return side * side * side;
    }

    @Test
    void commonRadiiFitBothProviders() {
        for (int r : new int[]{8, 16, 24, 32}) {
            long v = cube(r);
            assertTrue(v <= EntityCountWorldProvider.MAX_VOLUME,
                    "entities 上限要容得下半径 " + r + " 的盒子（" + v + "）");
            assertTrue(v <= BlockCountWorldProvider.MAX_VOLUME,
                    "blocks 上限要容得下半径 " + r + " 的盒子（" + v + "）");
        }
    }

    @Test
    void maxSupportedRadiusIsThirtyTwo() {
        assertTrue(cube(32) <= EntityCountWorldProvider.MAX_VOLUME);
        assertTrue(cube(33) > EntityCountWorldProvider.MAX_VOLUME, "33 格半径应当明确超出上限，而不是悄悄截断");
    }
}
