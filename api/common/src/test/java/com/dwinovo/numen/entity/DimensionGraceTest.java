package com.dwinovo.numen.entity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-02（B7）：进下界后 100 tick 内，D-20 传送兜底不累计/不传送。 */
class DimensionGraceTest {

    @Test
    void justChangedDimensionIsInGrace() {
        assertTrue(DimensionGrace.inGrace(1000, 1000));
        assertTrue(DimensionGrace.inGrace(1039, 1000));
    }

    @Test
    void graceEndsAfterThreshold() {
        assertFalse(DimensionGrace.inGrace(1040, 1000));
        assertFalse(DimensionGrace.inGrace(5000, 1000));
    }

    @Test
    void neverChangedDimensionNeverInGrace() {
        assertFalse(DimensionGrace.inGrace(5000, Long.MIN_VALUE));
    }
}