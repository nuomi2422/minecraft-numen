package com.dwinovo.numen.plugins.worldstate.provider;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.worldstate.core.model.*;
import com.dwinovo.numen.worldstate.core.observation.payload.SelfPayload;
import com.dwinovo.numen.worldstate.core.observation.payload.SelfPayload.*;
import com.dwinovo.numen.worldstate.core.provider.*;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import java.util.*;

/**
 * Explicit server-thread SELF collector (043 B2a, 2026-11-06); no automatic sampler.
 * WHY: immutable equipment wear/effect expiry must come from one server observation.
 * USAGE: registered with the existing WorldStateService refresh path; reads remain cache-only.
 * Not Watch/client caches: those omit wear/renewal and cannot establish current server identity.
 * Task elapsedMs is game-time ticks * 50, an estimate, NOT elapsed wall-clock milliseconds.
 */
public final class SelfWorldProvider implements WorldProvider {
    @Override public Topic topic() { return Topic.SELF; }
    @Override public String id() { return "self"; }
    @Override public int dataVersion() { return 1; }

    @Override public ProviderResult collect(CollectionContext context, ObservationRequest request) {
        var mc = McCollectionContexts.require(context);
        if (mc.server() == null || !mc.server().isSameThread())
            throw new ProviderException(FailReason.INVALID_REQUEST, "self requires server thread");
        // Revalidate even trusted in-process requests before touching a player.
        var canonical = new ObservationRequest(Topic.SELF, request.dimension(), request.target(), request.filter(), request.bounds());
        var player = mc.server().getPlayerList().getPlayer(UUID.fromString(canonical.target().substring(7)));
        if (!(player instanceof NumenPlayer))
            throw new ProviderException(FailReason.PROVIDER_ERROR, "companion not online");
        if (!player.serverLevel().dimension().location().toString().equals(canonical.dimension()))
            throw new ProviderException(FailReason.INVALID_REQUEST, "self dimension mismatch");
        var inventory = player.getInventory();
        List<Slot> main = new ArrayList<>(), armor = new ArrayList<>();
        for (int i = 0; i < 36; i++) main.add(slot(i, inventory.getItem(i)));
        for (int i = 36; i < 40; i++) armor.add(slot(i, inventory.getItem(i)));
        List<Effect> effects = new ArrayList<>();
        for (var effect : player.getActiveEffects()) {
            boolean infinite = effect.isInfiniteDuration();
            Integer duration = infinite ? null : effect.getDuration();
            Long expiry = duration == null ? null : Math.addExact(mc.gameTick(), duration.longValue());
            effects.add(new Effect(BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value()).toString(),
                    effect.getAmplifier(), duration, infinite, expiry));
        }
        effects.sort(Comparator.comparing(Effect::id));
        var vehicle = player.getVehicle();
        Vehicle riding = vehicle == null ? new Vehicle("", null)
                : new Vehicle(BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString(), vehicle.getUUID());
        TaskRecord task = CompanionTickDispatcher.currentTaskFor(player.getUUID());
        long now = player.level().getGameTime();
        Long elapsed = task == null || task.getStartedGameTime() < 0 || task.getStartedGameTime() > now
                ? null : Math.multiplyExact(now - task.getStartedGameTime(), 50L);
        BodyTask body = task == null ? new BodyTask("", "", "", false, 0L)
                : new BodyTask(task.publicId(), task.getToolName(), task.describe(),
                        task.getDeadlineGameTime() >= TaskRecord.NO_DEADLINE, elapsed);
        SelfPayload self = new SelfPayload(canonical.dimension(), player.getUUID(), main, armor,
                slot(40, inventory.getItem(40)), inventory.selected, player.getHealth(), player.getMaxHealth(),
                player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel(), effects, riding, body);
        return ProviderResult.of(self, Coverage.COMPLETE);
    }

    private static Slot slot(int index, ItemStack stack) {
        if (stack == null) throw new ProviderException(FailReason.PROVIDER_ERROR, "missing inventory slot");
        if (stack.isEmpty()) return Slot.empty(index);
        return new Slot(index, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount(),
                stack.getDamageValue(), stack.getMaxDamage(), stack.isDamageableItem());
    }
}
