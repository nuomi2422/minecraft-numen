package com.dwinovo.numen.core.task.sugarcane;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07：count 必填>=1、上限/期限/半径固定，禁止无限收割（记录 008 §9.2）。 */
class SugarCaneTaskRecordTest {
    @Test void valid() {
        var r = new SugarCaneTaskRecord(3, 2, 8, 1000 + 6000, 1000);
        assertEquals(3, r.count);
        assertEquals(2, r.maxTargets);
    }
    @Test void rejectsZeroOrHugeCount() {
        assertThrows(IllegalArgumentException.class, () -> new SugarCaneTaskRecord(0, 1, 8, 1000 + 2000, 1000));
        assertThrows(IllegalArgumentException.class, () -> new SugarCaneTaskRecord(65, 1, 8, 1000 + 2000, 1000));
    }
    @Test void rejectsBadRadiusTargetsAndDeadline() {
        assertThrows(IllegalArgumentException.class, () -> new SugarCaneTaskRecord(1, 0, 8, 1000 + 2000, 1000));
        assertThrows(IllegalArgumentException.class, () -> new SugarCaneTaskRecord(1, 1, 0, 1000 + 2000, 1000));
        assertThrows(IllegalArgumentException.class, () -> new SugarCaneTaskRecord(1, 1, 8, 1000, 1000));
        assertThrows(IllegalArgumentException.class, () -> new SugarCaneTaskRecord(1, 1, 8, 1000 + 2001, 1000));
    }
}
