package com.dwinovo.numen.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.Minecraft;

/**
 * R-06 production scheduling.
 *
 * <p>sampleAsync captures on the concrete server thread via server.execute (never blocks the caller);
 * sampleNow only returns a real sample when already on that server thread, otherwise an empty sample.
 * Multiplayer has no trusted handshake: identity stays empty and callers must stay UNKNOWN.
 */
final class MinecraftEnqueueScheduling implements EnqueueScheduling {

    @Override
    public Sample sampleNow(UUID companion) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null || !server.isSameThread()) return Sample.missing();
        return capture(companion, server);
    }

    @Override
    public CompletableFuture<Sample> sampleAsync(UUID companion) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return CompletableFuture.completedFuture(Sample.missing());
        CompletableFuture<Sample> future = new CompletableFuture<>();
        // server.execute runs on the server thread; the caller returns immediately (no join/get).
        server.execute(() -> future.complete(capture(companion, server)));
        return future;
    }

    private static Sample capture(UUID companion, net.minecraft.server.MinecraftServer server) {
        var token = WorldContextStamp.capture(server);
        var identity = token.identity();
        String world = identity == null ? null : identity.get("world_id");
        String session = identity == null ? null : identity.get("server_session");
        var entity = com.dwinovo.numen.client.agent.ClientNumenLookup.resolve(companion);
        Object level = entity == null ? null : entity.level();
        return new Sample(world, session, token.epoch(),
                "server:" + System.identityHashCode(server), entity, level, server);
    }

    @Override
    public boolean onClientThread() {
        return Minecraft.getInstance().isSameThread();
    }

    @Override
    public void executeOnClient(Runnable task) {
        Minecraft.getInstance().execute(task);
    }

    @Override
    public UUID liveTargetUuid(UUID companion) {
        var entity = com.dwinovo.numen.client.agent.ClientNumenLookup.resolve(companion);
        return entity == null ? null : entity.getUUID();
    }
}
