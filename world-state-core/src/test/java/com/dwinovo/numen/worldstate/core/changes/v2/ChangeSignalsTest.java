package com.dwinovo.numen.worldstate.core.changes.v2;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** D-020 的离线替身：用假页断言"有变化/断档 → 重扫，无变化 → 不重扫"。 */
class ChangeSignalsTest {

    private static ChangeEvent ev(long seq) {
        return new ChangeEvent(seq, "containers", "block:1", ChangeEvent.Kind.UPDATED, seq, 1L);
    }

    @Test
    void changeTriggersRescan() {
        ChangesPage page = new ChangesPage(List.of(ev(3)), 3, false, 3, false, "e1");
        assertTrue(ChangeSignals.shouldRescan(page), "有变化就必须立刻重扫（D-020 的正触发）");
    }

    @Test
    void resetTriggersRescan() {
        ChangesPage page = new ChangesPage(List.of(), 9, true, 9, false, "e1");
        assertTrue(ChangeSignals.shouldRescan(page), "断档也要重扫：手里的数据不可信");
    }

    @Test
    void noChangeDoesNotRescan() {
        ChangesPage page = new ChangesPage(List.of(), 4, false, 4, false, "e1");
        assertFalse(ChangeSignals.shouldRescan(page), "没变化就别扫，保持轮询节奏");
    }

    @Test
    void nullPageIsUnusable() {
        assertTrue(ChangeSignals.isUnusable(null));
        assertFalse(ChangeSignals.shouldRescan(null));
    }
}
