package com.dwinovo.numen.plugins.rdd;

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
    void remove(UUID id) { if (id != null) cache.remove(id); }
    void clear() { cache.clear(); }
    String render(UUID id, String currentWorldId, String currentSession) {
        Cached c = id == null ? null : cache.get(id);
        if (c == null) return null;
        return SideContextDecorator.decorate(c.block(), c.worldId(), c.serverSession(), c.dimension(), c.sampledGameTime(),
                currentWorldId, currentSession);
    }
}
