package com.dwinovo.numen.rdd.api;

/** Pure policy: daily tasks never count toward mainline completion; farm-gated daily is inactive before farm exists. */
public final class NodeTypePolicy {
    private NodeTypePolicy() {}

    public static boolean countsTowardMainline(NodeType type) {
        return type != NodeType.DAILY;
    }

    public static boolean canActivate(NodeType type, RecurrenceSpec recurrence, boolean farmBuilt) {
        if (type != NodeType.DAILY || recurrence == null) return true;
        return !recurrence.requiresFarmBuilt() || farmBuilt;
    }
}
