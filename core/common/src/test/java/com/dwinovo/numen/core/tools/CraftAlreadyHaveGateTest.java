package com.dwinovo.numen.core.tools;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07 B2: already-have gate must not block legit second/replacement crafts. */
class CraftAlreadyHaveGateTest {
    @Test void blocksWhenAlreadyEnoughAndUsable() {
        String msg = CraftOps.alreadyHaveMessage(1, 1, false, false);
        assertNotNull(msg);
        assertTrue(msg.contains("force=true"), msg);
    }
    @Test void forceAllowsSecond() {
        assertNull(CraftOps.alreadyHaveMessage(1, 1, true, false));
    }
    @Test void damagedEquipmentStillAllowsReplacement() {
        assertNull(CraftOps.alreadyHaveMessage(1, 1, false, true));
    }
    @Test void taskWantsTwoSoOneIsNotEnough() {
        assertNull(CraftOps.alreadyHaveMessage(1, 2, false, false));
    }
}
