package com.dwinovo.numen.rdd.core;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 2026-11-01 实机：seq37 与 seq39 同死一处（15,48,106），相隔 93 分钟。 */
class DangerSpotsTest {

    @Test
    void sameSpotAcrossHoursIsStillADangerSpot() {
        var deaths = List.of(
                new DangerSpots.DeathAt(37, 15, 48, 106, 1000),
                new DangerSpots.DeathAt(39, 15, 48, 106, 112_000));
        var spots = DangerSpots.find(deaths, 8.0, 2);
        assertEquals(1, spots.size());
        assertEquals(2, spots.get(0).deaths());
        assertEquals(List.of(37, 39), spots.get(0).seqs());
        assertTrue(spots.get(0).spanSeconds() > 3000);
    }

    @Test
    void differentPlacesAreNotClustered() {
        var deaths = List.of(
                new DangerSpots.DeathAt(1, 0, 64, 0, 100),
                new DangerSpots.DeathAt(2, 100, 64, 100, 200));
        assertTrue(DangerSpots.find(deaths, 8.0, 2).isEmpty());
    }

    @Test
    void singleDeathIsNotADangerSpot() {
        assertTrue(DangerSpots.find(List.of(new DangerSpots.DeathAt(1, 0, 64, 0, 100)), 8.0, 2).isEmpty());
        assertTrue(DangerSpots.find(null, 8.0, 2).isEmpty());
    }

    @Test
    void describeIsEmptyWithoutSpots() {
        assertEquals("", DangerSpots.describe(List.of()));
        var spots = DangerSpots.find(List.of(
                new DangerSpots.DeathAt(3, 15, 48, 106, 100),
                new DangerSpots.DeathAt(4, 16, 48, 106, 200)), 8.0, 2);
        assertTrue(DangerSpots.describe(spots).startsWith("danger_spot=15,48,106 deaths=2"));
    }
}