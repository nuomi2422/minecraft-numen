package com.dwinovo.numen.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import net.minecraft.client.Minecraft;

/**
 * R-08 production scheduling: two roles, two queues.
 *
 * <p>captureClient runs on the client thread and only reads client state.
 * sampleServerAsync runs on the concrete server thread (server.execute) and only reads server state.
 * No cross-thread entity reads, no join/get.
 */
final class MinecraftEnqueueScheduling implements EnqueueScheduling {

    @Override
    public ClientBinding captureClient(UUID companion) {
        Minecraft mc = Minecraft.getInstance();
        var entity = com.dwinovo.numen.client.agent.ClientNumenLookup.resolve(companion);
        Object level = entity == null ? null : entity.level();
        String connection = (mc.getConnection() == null ? "no-connection" : "conn:" + System.identityHashCode(mc.getConnection()))
                + "|" + (mc.getSingleplayerServer() == null ? "no-server" : "server:" + System.identityHashCode(mc.getSingleplayerServer()));
        return new ClientBinding(connection, mc, entity, level);
    }

    @Override
    public CompletableFuture<ServerSample> sampleServerAsync(UUID companion) {
        var server = Minecraft.getInstance().getSingleplayerServer();
        if (server == null) return CompletableFuture.completedFuture(new ServerSample(null, null, 0L, null, null));
        CompletableFuture<ServerSample> future = new CompletableFuture<>();
        try {
            server.execute(() -> {
                try {
                    future.complete(captureServer(companion, server));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (Throwable t) {
            future.completeExceptionally(t);
        }
        return future;
    }

    private static ServerSample captureServer(UUID companion, net.minecraft.server.MinecraftServer server) {
        var token = WorldContextStamp.capture(server);
        var identity = token.identity();
        String world = identity == null ? null : identity.get("world_id");
        String session = identity == null ? null : identity.get("server_session");
        Object entity = null;
        for (var level : server.getAllLevels()) {
            entity = level.getEntity(companion);
            if (entity != null) break;
        }
        return new ServerSample(world, session, token.epoch(), server, entity);
    }

    @Override
    public boolean onClientThread() {
        return Minecraft.getInstance().isSameThread();
    }

    @Override
    public void executeOnClient(Runnable task) {
        Minecraft.getInstance().execute(task);
    }
}
