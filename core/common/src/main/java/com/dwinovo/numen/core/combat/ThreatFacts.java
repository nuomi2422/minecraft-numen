package com.dwinovo.numen.core.combat;

/** Pure threat classification shared by perception and combat decisions. */
public final class ThreatFacts {

    public enum Threat {
        IMMEDIATE,
        OCCLUDED,
        REMOTE,
        NONE
    }

    private ThreatFacts() {}

    /**
     * Cover never turns a fusing explosive into a safe target. Other hostiles are immediate
     * only inside their danger radius with a clear line; walls take precedence over proximity.
     */
    public static Threat classify(boolean hostile,
                                  boolean targeting,
                                  boolean explosive,
                                  boolean armed,
                                  boolean lineOfSight,
                                  int solidBlocks,
                                  double distanceSq,
                                  double dangerRadiusSq) {
        if (!hostile) {
            return Threat.NONE;
        }
        if (explosive && armed) {
            return Threat.IMMEDIATE;
        }
        boolean withinDanger = distanceSq <= dangerRadiusSq;
        if (withinDanger && lineOfSight && solidBlocks == 0) {
            return Threat.IMMEDIATE;
        }
        if (solidBlocks > 0 || (withinDanger && !lineOfSight) || (targeting && !lineOfSight)) {
            return Threat.OCCLUDED;
        }
        return Threat.REMOTE;
    }
}
