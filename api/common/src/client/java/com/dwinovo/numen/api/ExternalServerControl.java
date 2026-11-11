package com.dwinovo.numen.api;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.client.Minecraft;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Authenticated MCP-only entry; deliberately absent from ToolRegistry/game-model catalogue. */
public final class ExternalServerControl {
    private ExternalServerControl() {}
    public static CompletableFuture<Map<String, Object>> call(UUID id, String world, String session,
                                                              String command, boolean reset) {
        var result = new CompletableFuture<Map<String, Object>>();
        Minecraft.getInstance().execute(() -> {
            var mc = Minecraft.getInstance();
            var server = mc.getSingleplayerServer();
            var owner = mc.player == null ? null : mc.player.getUUID();
            if (server == null || owner == null) {
                result.completeExceptionally(new IllegalStateException("local owner server required"));
                return;
            }
            server.execute(() -> {
                try {
                    var body = NumenPlayer.findByUuid(server, id);
                    var stamp = WorldContextStamp.capture(server).identity();
                    ExternalCommandPolicy.requireBound(world, session,
                            stamp == null ? null : stamp.get("world_id"),
                            stamp == null ? null : stamp.get("server_session"),
                            body != null && body.isOwnedByPlayer(owner));
                    if (reset) {
                        var resetter = NumenPlugins.capability(CompanionWorldReset.class);
                        if (resetter == null) throw new IllegalStateException("world reset provider unavailable");
                        mc.execute(() -> {
                            try {
                                if (mc.getSingleplayerServer() != server)
                                    throw new IllegalStateException("server changed before reset stop");
                                var loop = com.dwinovo.numen.client.agent.AgentLoopRegistry.get(id)
                                        .orElseThrow(() -> new IllegalStateException("owner loop unavailable"));
                                loop.ownerStop();
                                server.execute(() -> {
                                    try {
                                        var current = NumenPlayer.findByUuid(server, id);
                                        var now = WorldContextStamp.capture(server).identity();
                                        ExternalCommandPolicy.requireBound(world, session,
                                                now == null ? null : now.get("world_id"),
                                                now == null ? null : now.get("server_session"),
                                                current == body && body.isOwnedByPlayer(owner));
                                        result.complete(resetter.reset(body));
                                    } catch (Exception ex) { result.completeExceptionally(ex); }
                                });
                            } catch (Exception ex) { result.completeExceptionally(ex); }
                        });
                    } else {
                        String text = ExternalCommandPolicy.command(command);
                        var source = body.createCommandSourceStack().withPermission(4).withSuppressedOutput();
                        int code = server.getCommands().getDispatcher().execute(text, source);
                        org.slf4j.LoggerFactory.getLogger("numen-external-control").info(
                                "INTERVENED owner={} companion={} world={} session={} command={} result={}",
                                owner, id, world, session, text, code);
                        result.complete(Map.of("intervened", true, "result", code,
                                "companionUuid", id.toString(), "worldId", world,
                                "serverSession", session, "note", "command result is not post-state verification"));
                    }
                } catch (Exception ex) {
                    org.slf4j.LoggerFactory.getLogger("numen-external-control").warn(
                            "external control failed companion={} reset={}", id, reset, ex);
                    result.completeExceptionally(ex);
                }
            });
        });
        return result;
    }
}
