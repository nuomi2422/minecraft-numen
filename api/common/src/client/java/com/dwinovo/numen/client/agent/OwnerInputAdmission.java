package com.dwinovo.numen.client.agent;

import java.util.Objects;
import java.util.UUID;

/** P1-F (2026-11-11): capture-time input identity, independent of outgoing LLM generations.
 * Owned by one concrete loop; all production access is on the client thread.
 */
public final class OwnerInputAdmission {
    public record Ticket(UUID target, OwnerInputAdmission owner, long epoch) {}
    private final UUID target;
    private long epoch;

    public OwnerInputAdmission(UUID target) { this.target = Objects.requireNonNull(target); }
    public Ticket capture() { return new Ticket(target, this, epoch); }
    public void ownerStopped() { epoch++; }
    public boolean accepts(UUID recipient, Ticket ticket) {
        return ticket != null && ticket.owner() == this && target.equals(recipient)
                && target.equals(ticket.target()) && epoch == ticket.epoch();
    }
}
