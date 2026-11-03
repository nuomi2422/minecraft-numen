package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（death49–56）：空手回死亡点捡装备 = 送死。 */
class RddDeathRecoveryGuardTest {

    @Test
    void emptyHandsWithHazardousDeathCannotRecoverFirst() {
        assertFalse(RddDeathRecoveryGuard.allowRecoverFirst(true, "lava"));
        assertFalse(RddDeathRecoveryGuard.allowRecoverFirst(true, "onFire"));
        assertFalse(RddDeathRecoveryGuard.allowRecoverFirst(true, "mob"));
        assertFalse(RddDeathRecoveryGuard.allowRecoverFirst(true, "fall"));
    }

    @Test
    void gearedCompanionMayStillRecoverFirst() {
        assertTrue(RddDeathRecoveryGuard.allowRecoverFirst(false, "lava"));
        assertTrue(RddDeathRecoveryGuard.allowRecoverFirst(false, "mob"));
    }

    @Test
    void unknownCauseDoesNotBlock() {
        assertTrue(RddDeathRecoveryGuard.allowRecoverFirst(true, ""));
        assertTrue(RddDeathRecoveryGuard.allowRecoverFirst(true, null));
    }

    private static java.util.UUID seedDeath(java.util.UUID id, long gameTime, String deathAt) {
        com.dwinovo.numen.rdd.core.RddDeathLedger.record(
                id, gameTime, gameTime, deathAt, 1, java.util.Map.of("minecraft:stone", 1));
        return id;
    }

    @Test
    void repeatedDeathsNearbyBlockRecoverFirst() {
        java.util.UUID id = seedDeath(java.util.UUID.randomUUID(), 1_000L, "72, 63, 51");
        seedDeath(id, 1_020L, "70, 63, 51");
        assertTrue(com.dwinovo.numen.rdd.core.RddDeathLedger.latestDeathRepeatNearby(id, 3_600L, 8.0));

        java.util.UUID far = seedDeath(java.util.UUID.randomUUID(), 1_000L, "10, 64, 10");
        seedDeath(far, 1_020L, "300, 64, 300");
        assertFalse(com.dwinovo.numen.rdd.core.RddDeathLedger.latestDeathRepeatNearby(far, 3_600L, 8.0));
    }

    @Test
    void oldDeathOutsideWindowDoesNotCount() {
        // 同点但相隔 10 小时（720000 ticks > 3600 窗口）：不算「同点连死」
        java.util.UUID id = seedDeath(java.util.UUID.randomUUID(), 1_000L, "72, 63, 51");
        seedDeath(id, 721_000L, "72, 63, 51");
        assertFalse(com.dwinovo.numen.rdd.core.RddDeathLedger.latestDeathRepeatNearby(id, 3_600L, 8.0));
    }

    @Test
    void sameXzButDeeperStillCountsAsSameSpot() {
        // 垂直差很大但 x/z 相同：坠落/矿洞回冲必须命中（水平距离语义）
        java.util.UUID id = seedDeath(java.util.UUID.randomUUID(), 1_000L, "72, 63, 51");
        seedDeath(id, 1_020L, "72, -9, 51");
        assertTrue(com.dwinovo.numen.rdd.core.RddDeathLedger.latestDeathRepeatNearby(id, 3_600L, 8.0));
    }

    @Test
    void guardReasonDistinguishesUnknownFromSafe() {
        assertEquals("unknown_cause_allow", RddDeathRecoveryGuard.recoverGuardReason(true, ""));
        assertEquals("hazard_cause_refused", RddDeathRecoveryGuard.recoverGuardReason(true, "mob"));
        assertEquals("non_hazard_cause_allow", RddDeathRecoveryGuard.recoverGuardReason(true, "starved"));
        assertEquals("geared", RddDeathRecoveryGuard.recoverGuardReason(false, "lava"));
    }

    @Test
    void ledgerOrderIsNewestFirst() {
        // all() 倒序：下标 0 最新；旧实现拿 size-1 会错过这条
        assertTrue(RddDeathRecoveryGuard.repeatedNearby(
                java.util.List.of("70, 63, 51", "72, 63, 51", "100, 100, 100"), 24));
    }
}