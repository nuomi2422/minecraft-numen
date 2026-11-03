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

    @Test
    void repeatedDeathsNearbyBlockRecoverFirst() {
        assertTrue(RddDeathRecoveryGuard.repeatedNearby(
                java.util.List.of("72, 63, 51", "70, 63, 51"), 24));
        assertTrue(RddDeathRecoveryGuard.repeatedNearby(
                java.util.List.of("10, 64, 10", "100, 64, 100", "12, 64, 11"), 24));
        assertFalse(RddDeathRecoveryGuard.repeatedNearby(
                java.util.List.of("10, 64, 10", "100, 64, 100"), 24));
        assertFalse(RddDeathRecoveryGuard.repeatedNearby(
                java.util.List.of("?", "70, 63, 51"), 24));
    }

    @Test
    void sameXzButDeeperStillCountsAsSameSpot() {
        // 垂直差 >24 但 x/z 相同：坠落/矿洞回冲必须命中
        assertTrue(RddDeathRecoveryGuard.repeatedNearby(
                java.util.List.of("72, 63, 51", "72, -9, 51"), 24));
        assertFalse(RddDeathRecoveryGuard.repeatedNearby(
                java.util.List.of("72, 63, 51", "110, 63, 51"), 24));
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