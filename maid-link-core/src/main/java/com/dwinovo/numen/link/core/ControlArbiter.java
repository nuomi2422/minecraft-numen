package com.dwinovo.numen.link.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import static com.dwinovo.numen.link.core.LinkTypes.*;

/** Scoped to ONE authoritative server session, not one brain/binding. Use fully qualified resources.
 * 2026-11-10: expiry/revoke removes permission, not occupancy; only actual stop acknowledgment frees it.
 * Host must fence every native/third-party writer too. This offline core alone cannot stop a body.
 */
public final class ControlArbiter {
    public static final class Lease {
        private final String owner;
        private final Set<String> resources;
        private final long epoch, deadline;
        private boolean revoked;
        private Lease(String owner, Set<String> resources, long epoch, long deadline) {
            this.owner = owner; this.resources = resources; this.epoch = epoch; this.deadline = deadline;
        }
        public String owner() { return owner; }
        public Set<String> resources() { return resources; }
        public long epoch() { return epoch; }
    }
    public record Grant(Check check, Lease lease) {}
    private final Map<String, Lease> occupied = new HashMap<>();
    private final Set<Lease> active = new HashSet<>();
    private final LongSupplier clock;
    private final int capacity;
    private long lastTime = Long.MIN_VALUE, epoch;
    private boolean open = true;
    public ControlArbiter(LongSupplier monotonicClock, int capacity) {
        clock = java.util.Objects.requireNonNull(monotonicClock);
        if (capacity < 1) throw new IllegalArgumentException("capacity");
        this.capacity = capacity;
    }
    private long now() {
        long n = clock.getAsLong();
        if (n < lastTime) { open = false; throw new IllegalStateException("clock-regressed-authority-closed"); }
        lastTime = n; return n;
    }
    public synchronized Grant acquire(String owner, Set<String> resources, long duration) {
        LinkTypes.text(owner); resources = Set.copyOf(resources); resources.forEach(LinkTypes::text);
        if (resources.isEmpty() || duration <= 0) throw new IllegalArgumentException("resources/duration");
        if (!open) return new Grant(new Check(Code.STALE, "authority-closed"), null);
        if (resources.stream().anyMatch(occupied::containsKey)) return new Grant(new Check(Code.BUSY, "resources-not-stopped"), null);
        if (occupied.size() + resources.size() > capacity) return new Grant(new Check(Code.CAPACITY, "resource-limit"), null);
        long deadline = Math.addExact(now(), duration);
        Lease lease = new Lease(owner, resources, Math.incrementExact(epoch), deadline);
        epoch = lease.epoch; active.add(lease);
        for (String resource : resources) occupied.put(resource, lease);
        return new Grant(Check.yes(), lease);
    }
    /** Called at effect boundary, not just when a model asks for permission. */
    public synchronized boolean allows(Lease lease, String owner, String resource) {
        return open && active.contains(lease) && !lease.revoked && lease.owner.equals(owner)
                && occupied.get(resource) == lease && now() < lease.deadline;
    }
    public synchronized boolean revoke(Lease lease) {
        if (!active.contains(lease)) return false;
        lease.revoked = true; return true;
    }
    /** Trusted adapter verifies task/epoch stop before calling. Not a model-callable cancel. */
    public synchronized boolean acknowledgeStopped(Lease lease) {
        if (!active.remove(lease)) return false;
        lease.revoked = true;
        lease.resources.forEach(resource -> occupied.remove(resource, lease));
        return true;
    }
    public synchronized void close() {
        open = false; active.forEach(lease -> lease.revoked = true);
    }
}
