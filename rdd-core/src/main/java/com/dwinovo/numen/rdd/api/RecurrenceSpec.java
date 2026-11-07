package com.dwinovo.numen.rdd.api;

/** Recurring/daily node schedule + farm gate. Pure JVM, serialized by the planner parser. */
public record RecurrenceSpec(int intervalGameDays, MissedPolicy missedPolicy, boolean requiresFarmBuilt) {
    public enum MissedPolicy { SKIP, CATCH_UP }

    public RecurrenceSpec {
        if (intervalGameDays <= 0) throw new IllegalArgumentException("intervalGameDays must be positive");
        if (missedPolicy == null) throw new IllegalArgumentException("missedPolicy required");
    }

    public static RecurrenceSpec daily(boolean requiresFarmBuilt) {
        return new RecurrenceSpec(1, MissedPolicy.SKIP, requiresFarmBuilt);
    }
}
