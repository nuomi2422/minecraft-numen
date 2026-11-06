package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-07：收割入包补记按前后差计，部分接收不记整份，满包记 0。 */
class FarmHarvestSupportTest {
    @Test void acceptedCountsByDifference() {
        assertEquals(3, FarmHarvestSupport.accepted(3, 0), "全接收");
        assertEquals(2, FarmHarvestSupport.accepted(3, 1), "部分接收只记真实入包");
        assertEquals(0, FarmHarvestSupport.accepted(3, 3), "完全满包不记");
        assertEquals(0, FarmHarvestSupport.accepted(0, 0), "空掉落不记");
        assertEquals(0, FarmHarvestSupport.accepted(2, 5), "异常不回退负值");
    }
}
