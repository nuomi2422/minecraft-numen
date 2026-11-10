package com.dwinovo.numen.api;

import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * R-06 production scheduling: capture on the concrete single-player server, execute on the client thread.
 *
 * <p>Multiplayer has no trusted handshake: the sample has no identity and callers must stay UNKNOWN.
 */
final class MinecraftEnqueueScheduling implements EnqueueScheduling {

    @Override
    public Sample sampleNow(UUID companion) {
        Minecraft mc = Minecraft.getInstance();
        var entity = com.dwinovo.numen.client.agent.ClientNumenLookup.resolve(companion);
        var server = mc.getSingleplayerServer();
        if (server == null) {
            return new Sample(null, null, 0L, null, entity, null, null);
        }
        var token = WorldContextStamp.capture(server);
        var identity = token.identity();
        String world = identity == null ? null : identity.get("world_id");
        String session = identity == null ? null : identity.get("server_session");
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
