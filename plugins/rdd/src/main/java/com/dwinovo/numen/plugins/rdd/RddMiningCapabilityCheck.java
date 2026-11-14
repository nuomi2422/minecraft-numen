package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.api.SubtaskSpec;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Game-thread shell for the one obsidian planning check (044 B1/B2, 2026-11-14).
 * Reuses the existing companion body + planning inventory path and actual native stacks.
 * WHY not default stacks/tier names: components, mods and datapacks can change drops.
 * No level/block scan, new observation API or capability cache; unavailable stays UNKNOWN.
 * Capture, guard and delivery share the exact live body/server turn, never an async stale
 * inventory proof. The caller's original callback/lifecycle ticket still owns installation.
 */
final class RddMiningCapabilityCheck {
    private RddMiningCapabilityCheck() {}

    record Context(UUID companionId, NumenPlayer body, MinecraftServer server, WorldContextStamp.Token scope) {}

    /** Capture only identity before the LLM call; do not read mutable inventory off-thread. */
    static Context capture(UUID companionId) {
        try {
            NumenPlayer body = RddCompanionBody.get(companionId);
            MinecraftServer server = body == null ? null : body.getServer();
            return server == null ? null : new Context(companionId, body, server, WorldContextStamp.capture(server));
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    static void filter(Context context, List<SubtaskSpec> specs, String objective,
                       Map<String, Integer> planningHeld, Consumer<RddPlanGuard.Filtered> done) {
        if (specs.stream().noneMatch(RddMiningPrerequisite::needsCheck)) {
            done.accept(RddPlanGuard.filterSubtasks(specs, objective, planningHeld));
            return;
        }
        if (context == null || context.server() == null) {
            done.accept(RddPlanGuard.filterSubtasks(specs, objective, planningHeld));
            return;
        }
        MinecraftServer server = context.server();
        NumenPlayer body = context.body();
        UUID companionId = context.companionId();
        server.execute(() -> {
            RddMiningPrerequisite.Evidence evidence = null;
            Map<String, Integer> held = planningHeld;
            try {
                WorldContextStamp.Token scope = WorldContextStamp.capture(server);
                if (scope != null && scope.identity() != null && scope.equals(context.scope())
                        && body == RddCompanionBody.get(companionId)
                        && body.getServer() == server && body.isAlive() && !body.isRemoved()) {
                    held = RddDetector.countInventory(body); // existing inventory truth, not another world scanner
                    Map<String, Boolean> correct = new LinkedHashMap<>();
                    var target = Blocks.OBSIDIAN.defaultBlockState();
                    var inventory = body.getInventory();
                    for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
                        ItemStack stack = inventory.getItem(slot);
                        if (stack.isEmpty()) continue;
                        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                        boolean harvests = !target.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(target);
                        correct.merge(id, harvests, (a, b) -> a || b);
                    }
                    evidence = new RddMiningPrerequisite.Evidence(held, correct,
                            "native:ItemStack.isCorrectToolForDrops target=minecraft:obsidian companion=" + companionId
                                    + " scope=" + scope.identity() + " epoch=" + scope.epoch(), System.currentTimeMillis(), true);
                }
            } catch (RuntimeException | LinkageError unavailable) {
                // Missing native/scope support is not evidence of inability. No static hard fallback.
            }
            done.accept(RddPlanGuard.filterSubtasks(specs, objective, held, evidence));
        });
    }
}
