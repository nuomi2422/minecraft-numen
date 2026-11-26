package com.dwinovo.numen.core.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static com.dwinovo.numen.core.combat.ThreatFacts.Threat;

class ThreatFactsTest {

    private static Threat classify(boolean hostile, boolean targeting, boolean explosive,
                                   boolean armed, boolean sight, int solids,
                                   double distanceSq, double dangerRadiusSq) {
        return ThreatFacts.classify(hostile, targeting, explosive, armed, sight, solids,
                distanceSq, dangerRadiusSq);
    }

    @Test
    void visibleHostileInsideDangerIsImmediateIncludingTheBoundary() {
        assertEquals(Threat.IMMEDIATE, classify(true, false, false, false, true, 0, 9.0, 9.0));
    }

    @Test
    void wallBeatsProximityEvenIfSightFlagIsInconsistent() {
        assertEquals(Threat.OCCLUDED, classify(true, true, false, false, true, 2, 1.0, 9.0));
    }

    @Test
    void targetingWithoutSightIsOccludedEvenOutsideMeleeDanger() {
        assertEquals(Threat.OCCLUDED, classify(true, true, false, false, false, 1, 100.0, 9.0));
    }

    @Test
    void visibleHostileOutsideDangerIsOnlyRemote() {
        assertEquals(Threat.REMOTE, classify(true, false, false, false, true, 0, 10.0, 9.0));
        assertEquals(Threat.REMOTE, classify(true, true, false, false, true, 0, 10.0, 9.0));
    }

    @Test
    void fusingExplosiveIsImmediateEvenBehindCoverAndOutOfRange() {
        assertEquals(Threat.IMMEDIATE, classify(true, false, true, true, false, 4, 100.0, 9.0));
    }

    @Test
    void explosiveAloneOrArmedAloneDoesNotBypassCover() {
        assertEquals(Threat.OCCLUDED, classify(true, false, true, false, false, 1, 1.0, 9.0));
        assertEquals(Threat.OCCLUDED, classify(true, false, false, true, false, 1, 1.0, 9.0));
    }

    @Test
    void nonHostilesAreNeverThreats() {
        assertEquals(Threat.NONE, classify(false, true, true, true, true, 0, 0.0, 9.0));
    }
}
