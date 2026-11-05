package com.dwinovo.numen.core.task.interact;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-05：交互被挡住时给一个可站的坐标（把「换个开放侧」从文字变成位置）。
 */
class InteractSightHintTest {

    @Test
    void candidatesAreOrderedByDistance() {
        List<int[]> c = InteractSightHint.candidates(0, 64, 0);
        assertTrue(!c.isEmpty());
        for (int i = 1; i < c.size(); i++) {
            assertTrue(c.get(i - 1)[3] <= c.get(i)[3], "候选必须按距离升序");
        }
    }

    @Test
    void picksFirstStandableAndVisible() {
        int[] p = InteractSightHint.pick(10, 64, 10,
                pos -> pos[0] == 11 && pos[2] == 10,
                pos -> true);
        assertEquals(11, p[0]);
        assertEquals(10, p[2]);
    }

    @Test
    void skipsInvisibleCandidates() {
        Set<String> visible = Set.of("9,64,10");
        int[] p = InteractSightHint.pick(10, 64, 10,
                pos -> true,
                pos -> visible.contains(pos[0] + "," + pos[1] + "," + pos[2]));
        assertEquals("9,64,10", p[0] + "," + p[1] + "," + p[2]);
    }

    @Test
    void returnsNullWhenNothingWorks() {
        assertNull(InteractSightHint.pick(0, 64, 0, pos -> false, pos -> true));
        assertNull(InteractSightHint.pick(0, 64, 0, pos -> true, pos -> false));
    }

    @Test
    void neverSuggestsTheTargetCellItself() {
        for (int[] c : InteractSightHint.candidates(5, 70, 5)) {
            assertTrue(!(c[0] == 5 && c[2] == 5), "不能建议站在目标那一列的正中");
        }
    }
}
