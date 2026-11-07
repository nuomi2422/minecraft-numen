package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07 B2 ⑥: repeated-death-area and no-combat-gear guards (test-only, no impl change). */
class RddDeathRecoveryGuardTest {
    @Test void threeDeathsWithin24BlocksAreRepeatedArea() {
        List<String> at = List.of("10,64,10", "18,64,12", "30,64,30");
        assertTrue(RddDeathRecoveryGuard.repeatedNearby(at, 24));
    }

    @Test void scatteredDeathsAreNotRepeatedArea() {
        assertFalse(RddDeathRecoveryGuard.repeatedNearby(List.of("0,64,0", "500,64,0", "0,64,500"), 24));
        assertFalse(RddDeathRecoveryGuard.repeatedNearby(List.of(), 24));
    }

    @Test void emptyInventoryAndLavaDeathBlocksRecoverFirst() {
        assertFalse(RddDeathRecoveryGuard.allowRecoverFirst(true, "lava"));
        assertFalse(RddDeathRecoveryGuard.allowRecoverFirst(true, "onFire"));
        assertNotNull(RddDeathRecoveryGuard.recoverGuardReason(true, "lava"));
    }

    @Test void withCombatGearRecoverFirstAllowedButEmptyGearFallBlocked() {
        assertTrue(RddDeathRecoveryGuard.allowRecoverFirst(false, "lava"));
        assertFalse(RddDeathRecoveryGuard.allowRecoverFirst(true, "fall"), "空手 + 坠落同样危险");
    }
}
