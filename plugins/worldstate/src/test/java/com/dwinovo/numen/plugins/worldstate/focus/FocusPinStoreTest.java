package com.dwinovo.numen.plugins.worldstate.focus;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-06 显式短期焦点生命周期：两轮存活/到期/换世界/换会话/换主体/上限/去重。 */
class FocusPinStoreTest {

    private static final String W = "world-1";
    private static final String S = "session-1";
    private static final String D = "minecraft:overworld";

    @Test void pinSurvivesTwoRoundsThenExpires() {
        FocusPinStore st = new FocusPinStore();
        UUID c = UUID.randomUUID();
        FocusPin p = st.pin(c, W, S, D, "entities", 1000L, 5000L, 0, 8, 0);
        assertNotNull(p);
        assertEquals(1, st.active(c, W, S, 2000L).size(), "第一轮有效");
        assertEquals(1, st.active(c, W, S, 5900L).size(), "第二轮仍有效");
        assertEquals(0, st.active(c, W, S, 6001L).size(), "到期即失效");
    }

    @Test void pinsAreScopedByWorldSessionAndCompanion() {
        FocusPinStore st = new FocusPinStore();
        UUID c = UUID.randomUUID();
        st.pin(c, W, S, D, "environment", 1000L, 60_000L, 0, 0, 0);
        assertEquals(0, st.active(c, "other-world", S, 2000L).size());
        assertEquals(0, st.active(c, W, "other-session", 2000L).size());
        assertEquals(0, st.active(UUID.randomUUID(), W, S, 2000L).size());
        assertEquals(1, st.active(c, W, S, 2000L).size());
    }

    @Test void unpinAndLimitAndDuplicate() {
        FocusPinStore st = new FocusPinStore();
        UUID c = UUID.randomUUID();
        FocusPin a = st.pin(c, W, S, D, "entities", 1000L, 60_000L, 0, 0, 0);
        FocusPin again = st.pin(c, W, S, D, "entities", 1500L, 60_000L, 0, 0, 0);
        assertEquals(a.id(), again.id(), "同 topic+dimension 复用现有 pin");
        assertTrue(st.unpin(c, a.id()));
        assertFalse(st.unpin(c, a.id()));
        for (int i = 0; i < FocusPinStore.MAX_PINS_PER_COMPANION; i++) {
            assertNotNull(st.pin(c, W, S, "dim-" + i, "environment", 2000L + i, 60_000L, 0, 0, 0));
        }
        assertNull(st.pin(c, W, S, "dim-x", "environment", 3000L, 60_000L, 0, 0, 0), "超过上限应拒绝");
    }
}
