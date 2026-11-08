package com.dwinovo.numen.api;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** 2026-11-09 A1: process-local world/server-session stamp for monitor envelopes.
 * Hosts set it from the server thread at start/stop; absent = UNKNOWN. */
public final class WorldContextStamp {
    private static final AtomicReference<Map<String, String>> STAMP = new AtomicReference<>();

    public record Token(Map<String, String> identity, long epoch) {}
    private static final java.util.concurrent.atomic.AtomicLong EPOCH = new java.util.concurrent.atomic.AtomicLong();

    private WorldContextStamp() {}

    public static void set(String worldId, String serverSession) {
        if (worldId == null || worldId.isBlank() || serverSession == null || serverSession.isBlank()) {
            STAMP.set(null);
            return;
        }
        EPOCH.incrementAndGet();
        STAMP.set(Map.of("world_id", worldId, "server_session", serverSession));
    }

    public static Map<String, String> snapshot() { return STAMP.get(); }

    /** Capture at producer time; pass the token across async boundaries. */
    public static Token capture() { return new Token(STAMP.get(), EPOCH.get()); }
    public static long epoch() { return EPOCH.get(); }
    public static void clear() { EPOCH.incrementAndGet(); STAMP.set(null); }
}
