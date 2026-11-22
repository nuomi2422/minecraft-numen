package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import java.util.function.Consumer;

/** Optional player-owner entry; implementors must check ownership themselves. */
public interface OwnerAuthenticatedTool {
    void onOwnerCall(String id, JsonObject args, NumenPlayer companion, ServerPlayer caller, Consumer<String> reply);

    /** Issue an opaque owner-bound ticket only on the authenticated server path. */
    default void onOwnerTicketRequest(NumenPlayer companion, ServerPlayer caller, Consumer<String> reply) {
        reply.accept(com.dwinovo.numen.task.TaskResult.fail("yield ticket unavailable").toJson());
    }
}
