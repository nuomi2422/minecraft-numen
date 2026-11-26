package com.dwinovo.numen.entity;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable inventory fact captured immediately before vanilla death processing.
 *
 * <p>The snapshot belongs to one concrete body instance and one latched death generation.
 * Its inventory contains registry item ids and counts, not cached/derived asset keys. A
 * present snapshot with an empty inventory is a known-empty fact; callers that receive no
 * snapshot must treat the inventory as UNKNOWN rather than zero.</p>
 */
public record DeathInventorySnapshot(UUID companionId, int bodyEntityId, long deathGeneration,
                                     long capturedAtGameTick, String dimension,
                                     Map<String, Integer> inventory) {
    public DeathInventorySnapshot {
        Objects.requireNonNull(companionId, "companionId");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(inventory, "inventory");
        if (deathGeneration <= 0) throw new IllegalArgumentException("deathGeneration must be positive");
        Map<String, Integer> frozen = new LinkedHashMap<>();
        inventory.forEach((id, count) -> {
            if (id == null || id.isBlank() || count == null || count <= 0) {
                throw new IllegalArgumentException("inventory entries must have an id and positive count");
            }
            frozen.put(id, count);
        });
        inventory = Collections.unmodifiableMap(frozen);
    }

    /** True only for the exact body instance that produced this snapshot. */
    public boolean belongsTo(NumenPlayer body) {
        return body != null && companionId.equals(body.getUUID()) && bodyEntityId == body.getId();
    }

    /** A present empty snapshot means the pre-death inventory was observed empty. */
    public boolean isEmpty() {
        return inventory.isEmpty();
    }

    /** Server-thread capture before {@code ServerPlayer.die} drops/clears the inventory. */
    static DeathInventorySnapshot capture(NumenPlayer body, long deathGeneration) {
        Objects.requireNonNull(body, "body");
        Map<String, Integer> counts = new LinkedHashMap<>();
        var inventory = body.getInventory();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            counts.merge(id, stack.getCount(), Integer::sum);
        }
        return new DeathInventorySnapshot(body.getUUID(), body.getId(), deathGeneration,
                body.level().getGameTime(), body.level().dimension().location().toString(), counts);
    }
}
