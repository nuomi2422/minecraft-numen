package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.core.TaskChain;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** One owned Stage-B request's host identity, not a new queue, registry or planner. */
final class RddStageBAssetHost {
    record Context(UUID companionId, TaskChain owner, long revision, Object primary,
                   MinecraftServer server, NumenPlayer body, AssetRegistry registry,
                   RddCallbackGuard.Ticket ticket, WorldContextStamp.Token world, String catalog) {}
    private RddStageBAssetHost() {}

    static Context capture(UUID id, TaskChain owner, long revision) {
        if (owner == null) return null;
        NumenPlayer body = RddCompanionBody.get(id);
        MinecraftServer server = body == null ? null : body.getServer();
        if (server == null || !server.isSameThread()) return null;
        var world = WorldContextStamp.capture(server);
        if (world == null || world.identity() == null) return null;
        Context context = new Context(id, owner, revision, owner.currentPrimary(), server, body,
                RddPlugin.assets(id), RddPlugin.planningTicket(id), world,
                RddAssetContext.render(RddPlugin.assets(id), 1200));
        return current(context) ? context : null;
    }
    static boolean current(Context context) {
        if (context == null || ServerLifecycleHooks.getCurrentServer() != context.server()) return false;
        var runtime = RddPlugin.runtime(context.companionId());
        return runtime != null && runtime.chain() == context.owner()
                && context.owner().planRevision() == context.revision()
                && context.owner().currentPrimary() == context.primary()
                && RddPlugin.planningTicket(context.companionId()) == context.ticket()
                && RddCompanionBody.get(context.companionId()) == context.body()
                && context.body().getServer() == context.server() && context.body().isAlive() && !context.body().isRemoved()
                && RddPlugin.assets(context.companionId()) == context.registry()
                && context.world().equals(WorldContextStamp.capture(context.server()));
    }
    static void dispatch(Context context, Runnable action) {
        RddPlugin.onServer(context.server(), context.ticket(), () -> {
            if (current(context)) action.run();
        });
    }
    /** Called only inside the original server/ticket guard. No world scan or refresh. */
    static String read(Context context, com.google.gson.JsonObject args) {
        if (!current(context)) return null;
        String result = RddAssetsTool.dispatch(args, context.registry());
        return current(context) ? result : null;
    }
    /** Endpoint and budget are already selected by the existing transport. */
    static <T> CompletableFuture<T> send(Context context, Supplier<CompletableFuture<T>> request) {
        CompletableFuture<T> response = new CompletableFuture<>();
        dispatch(context, () -> {
            try {
                request.get().whenComplete((result, failure) -> {
                    if (failure != null) response.completeExceptionally(failure); else response.complete(result);
                });
            } catch (RuntimeException failure) { response.completeExceptionally(failure); }
        });
        return response;
    }
}
