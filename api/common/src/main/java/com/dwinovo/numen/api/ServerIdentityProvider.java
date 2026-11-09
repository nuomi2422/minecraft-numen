package com.dwinovo.numen.api;

import net.minecraft.server.MinecraftServer;

/**
 * 2026-11-09 A1: host-provided identity for a concrete server object.
 * Capture from the dispatch context; never fall back to a process-global world.
 */
public interface ServerIdentityProvider {
    record Snapshot(String worldId, String serverSession) {}

    /** Return null when this server is not the provider's active server or identity is unknown. */
    Snapshot snapshot(MinecraftServer server);
}
