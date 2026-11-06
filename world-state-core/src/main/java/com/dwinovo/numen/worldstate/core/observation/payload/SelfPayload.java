package com.dwinovo.numen.worldstate.core.observation.payload;

import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.ObservationPayload;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * B2a self data-contract candidate (043 section 6, 2026-11-06), NOT wired to production.
 * WHY: item counts alone miss wear and effect renewal; detached values retain those changes.
 * USAGE: wrap a copied server sample in the existing Observation (time/world/session live there).
 * Not ItemStack/TaskRecord: those mutable host objects would leak world access and write authority.
 * Null scalar/group means unknown, never zero/empty. Known empty effects is List.of().
 * Main slots 0..35 (0..8 hotbar); armor 36..39 = feet/legs/chest/head; offhand = 40.
 * Full main/armor groups must have every slot, including explicit empty slots; no compacted lists.
 */
public record SelfPayload(String dimension, UUID companionUUID, List<Slot> main, List<Slot> armor,
                          Slot offhand, Integer selected, Float hp, Float maxHp, Integer food,
                          Float saturation, List<Effect> effects, Vehicle vehicle, BodyTask bodyTask)
        implements ObservationPayload {
    public SelfPayload {
        if (dimension == null || dimension.isBlank() || companionUUID == null)
            throw new IllegalArgumentException("self identity required");
        dimension = dimension.trim().toLowerCase(Locale.ROOT);
        main = slots(main, 0, 36);
        armor = slots(armor, 36, 4);
        if (offhand != null && offhand.slot() != 40) throw new IllegalArgumentException("offhand slot must be 40");
        if (selected != null && (selected < 0 || selected > 8)) throw new IllegalArgumentException("selected must be 0..8");
        nonNegative(hp, "hp");
        nonNegative(maxHp, "maxHp");
        nonNegative(saturation, "saturation");
        if (food != null && food < 0) throw new IllegalArgumentException("negative food");
        effects = effects == null ? null : List.copyOf(effects);
    }

    @Override public Topic topic() { return Topic.SELF; }

    /** Main hand is an alias, not a second independently sampled stack. */
    public Slot mainHand() { return main == null || selected == null ? null : main.get(selected); }

    private static List<Slot> slots(List<Slot> slots, int start, int count) {
        if (slots == null) return null;
        List<Slot> copy = List.copyOf(slots);
        if (copy.size() != count) throw new IllegalArgumentException("wrong slot count");
        for (int i = 0; i < count; i++) {
            if (copy.get(i).slot() != start + i) throw new IllegalArgumentException("wrong slot order/id");
        }
        return copy;
    }

    private static void nonNegative(Float value, String name) {
        if (value != null && (!Float.isFinite(value) || value < 0)) throw new IllegalArgumentException("invalid " + name);
    }

    /** id is a registry id; empty known slot = empty id/count 0. Null fields remain unknown. */
    public record Slot(Integer slot, String id, Integer count, Integer damage, Integer maxDamage, Boolean isDamageable) {
        public Slot {
            if (slot == null || slot < 0 || slot > 40) throw new IllegalArgumentException("slot must be 0..40");
            if (count != null && count < 0 || damage != null && damage < 0 || maxDamage != null && maxDamage < 0)
                throw new IllegalArgumentException("negative stack values");
            if (id != null && id.isEmpty() && count != null && count != 0)
                throw new IllegalArgumentException("empty stack has nonzero count");
        }
        public static Slot empty(int slot) { return new Slot(slot, "", 0, 0, 0, false); }
    }

    /** Duration is ticks AT observation, not a ticking cache. Infinite has no finite expiry. */
    public record Effect(String id, Integer amplifier, Integer observedDuration, Boolean infinite, Long expiryTick) {
        public Effect {
            if (id == null || id.isBlank() || amplifier != null && amplifier < 0) throw new IllegalArgumentException("invalid effect identity");
            if (observedDuration != null && observedDuration < 0 || expiryTick != null && expiryTick < 0)
                throw new IllegalArgumentException("negative effect time");
            if (Boolean.TRUE.equals(infinite) && expiryTick != null) throw new IllegalArgumentException("infinite effect cannot expire");
        }
    }

    /** Null group = unknown; empty type with null UUID = observed not riding; never runtime entity id. */
    public record Vehicle(String type, UUID uuid) {
        public Vehicle {
            if (type == null || (type.isEmpty() ? uuid != null : type.isBlank() || uuid == null))
                throw new IllegalArgumentException("vehicle needs type and UUID together");
        }
    }

    /** Detached read-only projection of current-task fields; not a scheduler or completion verdict.
     * Null group = unknown; empty taskId = known idle (same convention as CurrentTaskPayload).
     */
    public record BodyTask(String taskId, String tool, String describe, Boolean standing, Long elapsedMs) {
        public BodyTask {
            if (taskId == null) throw new IllegalArgumentException("task id required, empty means idle");
            if (elapsedMs != null && elapsedMs < 0) throw new IllegalArgumentException("negative elapsed time");
        }
    }
}
