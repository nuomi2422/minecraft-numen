package com.dwinovo.numen.core.tools;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07: no global craft reservation block - ordinary crafting still allowed. */
class CraftNotBlockedTest {
    @Test void emptyInventoryAllowsCraft() {
        assertNull(CraftOps.alreadyHaveMessage(0, 1, false, false));
    }
    @Test void forceStillBypassesAlreadyHave() {
        assertNull(CraftOps.alreadyHaveMessage(2, 1, true, false));
    }
}
