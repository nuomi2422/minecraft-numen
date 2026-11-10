package com.dwinovo.numen.link.core;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import static com.dwinovo.numen.link.core.LinkTypes.*;

/** One trusted host authority owns this instance. A ticket is an in-process fence, not a credential.
 * 2026-11-10: switch only affected slots, reject stale callbacks, do not stop unrelated native work.
 * Production adapter MUST finish/drain actual work before finish(); timeout is not a drain receipt.
 * No production registration exists yet; contract fixtures are the current consumer.
 */
public final class LinkPolicy {
    public record Snapshot(long revision, Map<Slot, Choice> choices) {
        public Snapshot { choices = Map.copyOf(choices); }
    }
    public static final class Ticket {
        private final Slot slot;
        private final long epoch;
        private final Choice choice;
        private Ticket(Slot slot, long epoch, Choice choice) {
            this.slot = slot; this.epoch = epoch; this.choice = choice;
        }
        public Slot slot() { return slot; }
        public Choice choice() { return choice; }
    }
    public record Admission(Check check, Ticket ticket) {}
    private final Binding binding;
    private final CapabilityCatalog catalog;
    private final int maxInFlight;
    private final Map<Slot, Choice> choices = new HashMap<>();
    private final Map<Slot, Long> epochs = new HashMap<>();
    private final Set<Slot> draining = new HashSet<>();
    private final Set<Ticket> inFlight = new HashSet<>();
    private long revision;
    private boolean open = true;

    public LinkPolicy(Binding binding, CapabilityCatalog catalog, Map<Slot, Choice> initial,
                      Set<String> available, int maxInFlight) {
        this.binding = java.util.Objects.requireNonNull(binding);
        this.catalog = java.util.Objects.requireNonNull(catalog);
        if (maxInFlight < 1) throw new IllegalArgumentException("capacity");
        this.maxInFlight = maxInFlight;
        initial.forEach((slot, choice) -> {
            Check c = catalog.validate(binding, slot, choice, available);
            if (!c.ok()) throw new IllegalArgumentException(c.reason());
            choices.put(slot, choice); epochs.put(slot, 0L);
        });
    }
    public synchronized Snapshot snapshot() { return new Snapshot(revision, choices); }
    public synchronized Check drain(long expectedRevision, Set<Slot> slots) {
        if (!open || revision != expectedRevision) return new Check(Code.STALE, "policy-session-or-revision");
        if (!choices.keySet().containsAll(slots)) return new Check(Code.UNSUPPORTED, "unknown-slot");
        // Revocation fences output immediately; actual work remains counted until acknowledged stopped.
        for (Slot slot : slots) {
            if (draining.add(slot)) epochs.put(slot, Math.incrementExact(epochs.get(slot)));
        }
        return Check.yes();
    }
    /** Atomic patch, not a full replacement. Unrelated slots/tickets keep working. */
    public synchronized Check apply(long expectedRevision, Map<Slot, Choice> patch, Set<String> available) {
        if (!open || revision != expectedRevision) return new Check(Code.STALE, "policy-session-or-revision");
        var changes = new HashMap<Slot, Choice>();
        for (var entry : Map.copyOf(patch).entrySet()) {
            Check c = catalog.validate(binding, entry.getKey(), entry.getValue(), available);
            if (!c.ok()) return c;
            if (!entry.getValue().equals(choices.get(entry.getKey()))) changes.put(entry.getKey(), entry.getValue());
        }
        if (changes.isEmpty()) return Check.yes();
        if (inFlight.stream().anyMatch(t -> changes.containsKey(t.slot)))
            return new Check(Code.BUSY, "affected-work-not-drained");
        long next = Math.incrementExact(revision);
        for (Slot slot : changes.keySet()) if (epochs.getOrDefault(slot, 0L) == Long.MAX_VALUE)
            throw new IllegalStateException("epoch-exhausted");
        changes.forEach((slot, choice) -> {
            choices.put(slot, choice); epochs.put(slot, epochs.getOrDefault(slot, 0L) + 1);
            draining.remove(slot);
        });
        revision = next;
        return Check.yes();
    }
    /** Recover a drained slot without reviving its old tickets. Requires actual drain. */
    public synchronized Check resume(long expectedRevision, Slot slot) {
        if (!open || revision != expectedRevision) return new Check(Code.STALE, "policy-session-or-revision");
        if (!choices.containsKey(slot)) return new Check(Code.UNSUPPORTED, "unknown-slot");
        if (inFlight.stream().anyMatch(t -> t.slot.equals(slot))) return new Check(Code.BUSY, "not-drained");
        draining.remove(slot);
        return Check.yes();
    }
    public synchronized Admission begin(long expectedRevision, Slot slot, Set<String> available) {
        if (!open || expectedRevision != revision) return new Admission(new Check(Code.STALE, "policy-session-or-revision"), null);
        Choice choice = choices.get(slot);
        if (choice == null || !choice.enabled()) return new Admission(new Check(Code.DENIED, "disabled-or-absent"), null);
        if (draining.contains(slot)) return new Admission(new Check(Code.BUSY, "draining"), null);
        Check c = catalog.validate(binding, slot, choice, available);
        if (!c.ok()) return new Admission(c, null);
        if (inFlight.size() >= maxInFlight) return new Admission(new Check(Code.CAPACITY, "in-flight-limit"), null);
        Ticket ticket = new Ticket(slot, epochs.get(slot), choice);
        inFlight.add(ticket);
        return new Admission(Check.yes(), ticket);
    }
    /** Check again at the actual output/effect boundary in the same host serial domain. */
    public synchronized boolean valid(Ticket ticket) {
        return open && inFlight.contains(ticket) && !draining.contains(ticket.slot)
                && ticket.epoch == epochs.get(ticket.slot) && ticket.choice.equals(choices.get(ticket.slot));
    }
    /** Trusted adapter acknowledgment only. Keep old effect evidence in the host's original journal. */
    public synchronized boolean finish(Ticket ticket) { return inFlight.remove(ticket); }
    public synchronized void close() { open = false; }
}
