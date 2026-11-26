package com.dwinovo.numen.rdd.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Server-local, bounded short-lived yield tickets; not an authentication substitute. */
public final class YieldOwnerBinding {
    public record Identity(UUID companion, String serverSession, String chainInstance, long planRevision,
                           String subtaskId, String scope, String sideInstance, long sideGeneration) {
        public Identity {
            if (companion == null || blank(serverSession) || blank(chainInstance) || blank(subtaskId) || planRevision < 0
                    || !("MAINLINE".equals(scope) || "SIDE".equals(scope))
                    || sideInstance == null || sideGeneration < 0
                    || ("MAINLINE".equals(scope) && (!sideInstance.isEmpty() || sideGeneration != 0))
                    || ("SIDE".equals(scope) && (blank(sideInstance) || sideGeneration == 0)))
                throw new IllegalArgumentException("incomplete yield identity");
        }
        private static boolean blank(String value) { return value == null || value.isBlank(); }
    }
    private record Entry(Identity identity, long issuedAt) {}
    private final int capacity;
    private final long ttlMillis;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public YieldOwnerBinding(int capacity, long ttlMillis) {
        if (capacity <= 0 || ttlMillis <= 0) throw new IllegalArgumentException("positive ticket bounds required");
        this.capacity = capacity; this.ttlMillis = ttlMillis;
    }
    public synchronized String issue(Identity identity, long now) {
        if (identity == null || now < 0) throw new IllegalArgumentException("identity and time required");
        while (entries.size() >= capacity) entries.remove(entries.keySet().iterator().next());
        String ticket;
        do { ticket = UUID.randomUUID().toString(); } while (entries.containsKey(ticket));
        entries.put(ticket, new Entry(identity, now));
        return ticket;
    }
    /** A ticket is consumed on any validation attempt, including mismatch or expiry. */
    public synchronized boolean valid(String ticket, Identity current, long now) {
        if (ticket == null || ticket.isBlank()) return false;
        Entry e = entries.remove(ticket);
        return e != null && current != null && now >= e.issuedAt()
                && now - e.issuedAt() < ttlMillis && e.identity().equals(current);
    }
}
