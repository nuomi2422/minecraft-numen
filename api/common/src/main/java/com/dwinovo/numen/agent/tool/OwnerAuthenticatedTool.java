package com.dwinovo.numen.agent.tool;

import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonObject;
import net.minecraft.server.level.ServerPlayer;
import java.util.function.Consumer;

/** Optional player-owner entry; implementors must check ownership themselves. */
public interface OwnerAuthenticatedTool {
    void onOwnerCall(String id, JsonObject args, NumenPlayer companion, ServerPlayer caller, Consumer<String> reply);
}
