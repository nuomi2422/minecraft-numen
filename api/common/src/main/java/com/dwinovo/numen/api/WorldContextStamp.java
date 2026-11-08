package com.dwinovo.numen.api;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 2026-11-09 A1: process-local world/server-session stamp for monitor envelopes.
 * Hosts set it from the server thread at start/stop; absent = UNKNOWN. */
public final class WorldContextStamp {
    private static final AtomicReference<Map<String, String>> STAMP = new AtomicReference<>();

    private WorldContextStamp() {}

    public static void set(String worldId, String serverSession) {
        if (worldId == null || worldId.isBlank() || serverSession == null || serverSession.isBlank()) {
            STAMP.set(null);
            return;
        }
        STAMP.set(Map.of("world_id", worldId, "server_session", serverSession));
    }

    public static Map<String, String> snapshot() { return STAMP.get(); }
    public static void clear() { STAMP.set(null); }
}
