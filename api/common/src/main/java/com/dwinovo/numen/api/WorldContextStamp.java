package com.dwinovo.numen.api;

import java.util.Map;
import net.minecraft.server.MinecraftServer;
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

    public static Token tokenFrom(ServerIdentityProvider.Snapshot snap) {
        if (snap == null || snap.worldId() == null || snap.worldId().isBlank()
                || snap.serverSession() == null || snap.serverSession().isBlank()) {
            return new Token(null, 0L);
        }
        return new Token(Map.of("world_id", snap.worldId(), "server_session", snap.serverSession()), EPOCH.get());
    }

    /** Independent path: ask the host for identity of this exact server object. */
    private static volatile Token TEST_TOKEN;

    /** Package/test seam: inject a concrete identity without constructing a MinecraftServer. */
    public static void installForTest(Token token) { TEST_TOKEN = token; }
    public static void resetForTest() { TEST_TOKEN = null; }
    public static Token captureForTest() { return TEST_TOKEN; }

    public static Token capture(MinecraftServer server) {
        if (server == null) return new Token(null, 0L);
        var provider = NumenPlugins.capability(ServerIdentityProvider.class);
        if (provider == null) return new Token(null, 0L);
        return tokenFrom(provider.snapshot(server));
    }
    public static void clear() { EPOCH.incrementAndGet(); STAMP.set(null); }
}
