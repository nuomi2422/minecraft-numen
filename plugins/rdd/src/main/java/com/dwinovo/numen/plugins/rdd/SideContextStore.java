package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.side.MainlineResumeToken;
import com.dwinovo.numen.rdd.side.RecurringSideTaskSpec;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Pure cache/lifecycle seam for SIDE_CONTEXT; no MC/server classes. */
final class SideContextStore {
    record Cached(String block, String worldId, String serverSession, String dimension, long sampledGameTime) {}
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();

    void put(UUID id, String block, String worldId, String serverSession, String dimension, long sampledGameTime) {
        if (id != null && block != null) cache.put(id, new Cached(block, worldId, serverSession, dimension, sampledGameTime));
    }
    /** Sampling validity only: specs do not record registration-time chain/revision ownership. */
    record Ticket(String worldId, String serverSession, String dimension,
                  MainlineResumeToken owner, long gameDay) {}
    private record Registrations(Ticket ticket, List<RecurringSideTaskSpec> specs) {}
    private final Map<UUID, Registrations> registrations = new ConcurrentHashMap<>();

    void putRegistrations(UUID id, Ticket sampled, List<RecurringSideTaskSpec> specs) {
        if (!valid(id, sampled) || specs == null) {
            removeRegistrations(id);
            return;
        }
        registrations.put(id, new Registrations(sampled, List.copyOf(specs)));
    }

    /** null = UNKNOWN; an authenticated empty snapshot is a known empty list. */
    List<RecurringSideTaskSpec> registrationsFor(UUID id, Ticket current) {
        if (!valid(id, current)) return null;
        Registrations cached = registrations.get(id);
        if (cached == null || !sameOwner(cached.ticket(), current)) return null;
        return cached.specs();
    }

    private static boolean valid(UUID id, Ticket ticket) {
        return id != null && ticket != null && known(ticket.worldId())
                && known(ticket.serverSession()) && known(ticket.dimension())
                && ticket.owner() != null && id.equals(ticket.owner().companionId())
                && known(ticket.owner().chainInstanceId()) && known(ticket.owner().goalId())
                && ticket.owner().planRevision() >= 0 && ticket.gameDay() >= 0;
    }

    private static boolean known(String value) { return value != null && !value.isBlank(); }

    private static boolean sameOwner(Ticket sampled, Ticket current) {
        MainlineResumeToken a = sampled.owner(), b = current.owner();
        // Primary/subtask cursors and resume generation are not registration owners.
        return sampled.worldId().equals(current.worldId())
                && sampled.serverSession().equals(current.serverSession())
                && sampled.dimension().equals(current.dimension())
                && sampled.gameDay() == current.gameDay()
                && a.companionId().equals(b.companionId())
                && a.chainInstanceId().equals(b.chainInstanceId())
                && a.goalId().equals(b.goalId()) && a.planRevision() == b.planRevision();
    }

    void removeRegistrations(UUID id) { if (id != null) registrations.remove(id); }
    void remove(UUID id) {
        if (id != null) cache.remove(id);
        removeRegistrations(id);
    }
    void clear() {
        cache.clear();
        registrations.clear();
    }
    String render(UUID id, String currentWorldId, String currentSession) {
        Cached c = id == null ? null : cache.get(id);
        if (c == null) return null;
        return SideContextDecorator.decorate(c.block(), c.worldId(), c.serverSession(), c.dimension(), c.sampledGameTime(),
                currentWorldId, currentSession);
    }
}
