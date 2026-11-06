package com.dwinovo.numen.entity;

import com.dwinovo.numen.api.CompanionEvent;
import com.dwinovo.numen.platform.ServerLifecycle;
import com.dwinovo.numen.task.CompanionTickDispatcher;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.effect.MobEffectInstance;
import java.lang.ref.WeakReference;
import java.util.*;

/**
 * One detached host frame per body/server/dimension/tick (2026-11-06, SELF shared sampling slice).
 * WHY: Watch detection, old full-component packets and explicit SELF previously reread the body.
 * USAGE: capture on server thread; repeated same-tick consumers get the original epoch/tick.
 * Not a fact/history store: at most one frame per currently retained body; no automatic sampler.
 * Weak body/server keys and detached stack/effect/task values cannot keep a world/entity alive.
 * Copy getters prevent callers mutating the frame. Never put Minecraft objects into world-state-core.
 */
public final class CompanionBodySample {
    private record Entry(WeakReference<Object> server, WeakReference<NumenPlayer> body, Frame frame) {}
    private static final Map<UUID, Entry> LATEST = new HashMap<>();
    static {
        ServerLifecycle.onStopped(LATEST::clear);
        CompanionEvents.subscribe(CompanionEvent.REMOVE, p -> forget(p.getUUID()));
        CompanionEvents.subscribe(CompanionEvent.DEATH, p -> forget(p.getUUID()));
    }
    private CompanionBodySample() {}
    public static void forget(UUID id) { LATEST.remove(id); }

    public static Frame capture(NumenPlayer body) {
        var server = body.getServer();
        if (server == null || !server.isSameThread()) throw new IllegalStateException("body sample requires server thread");
        LATEST.entrySet().removeIf(e -> e.getValue().server().get() == null || e.getValue().body().get() == null);
        long tick = server.getTickCount();
        String dimension = body.serverLevel().dimension().location().toString();
        Entry prior = LATEST.get(body.getUUID());
        if (prior != null && prior.server().get() == server && prior.body().get() == body
                && prior.frame().tick() == tick && prior.frame().dimension().equals(dimension)) return prior.frame();
        long epochMs = System.currentTimeMillis();
        List<ItemStack> slots = new ArrayList<>(41);
        for (int i = 0; i < 41; i++) slots.add(body.getInventory().getItem(i).copy());
        List<MobEffectInstance> effects = new ArrayList<>();
        for (var effect : body.getActiveEffects()) effects.add(new MobEffectInstance(effect));
        var vehicle = body.getVehicle();
        String vehicleType = vehicle == null ? "" : net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType()).toString();
        TaskRecord task = CompanionTickDispatcher.currentTaskFor(body.getUUID());
        long gameTime = body.level().getGameTime();
        Long elapsed = task == null || task.getStartedGameTime() < 0 || task.getStartedGameTime() > gameTime
                ? null : Math.multiplyExact(gameTime - task.getStartedGameTime(), 50L);
        Task taskValue = task == null ? new Task("", "", "", false, 0L)
                : new Task(task.publicId(), task.getToolName(), task.describe(), task.getDeadlineGameTime() >= TaskRecord.NO_DEADLINE, elapsed);
        Frame frame = new Frame(body.getUUID(), dimension, tick, epochMs, slots, effects, body.getInventory().selected,
                body.getHealth(), body.getMaxHealth(), body.getFoodData().getFoodLevel(), body.getFoodData().getSaturationLevel(),
                vehicleType, vehicle == null ? null : vehicle.getUUID(), vehicle == null ? -1 : vehicle.getId(), taskValue);
        LATEST.put(body.getUUID(), new Entry(new WeakReference<>(server), new WeakReference<>(body), frame));
        return frame;
    }

    /** Detached strings/numbers only; elapsedMs is level game-time difference * 50, not wall time. */
    public record Task(String id, String tool, String describe, boolean standing, Long elapsedMs) {}

    public static final class Frame {
        private final UUID id, vehicleUUID;
        private final String dimension, vehicleType;
        private final long tick, epochMs;
        private final List<ItemStack> slots;
        private final List<MobEffectInstance> effects;
        private final int selected, food, vehicleId;
        private final float hp, maxHp, saturation;
        private final Task task;
        private Frame(UUID id, String dimension, long tick, long epochMs, List<ItemStack> slots,
                      List<MobEffectInstance> effects, int selected, float hp, float maxHp, int food, float saturation,
                      String vehicleType, UUID vehicleUUID, int vehicleId, Task task) {
            this.id=id; this.dimension=dimension; this.tick=tick; this.epochMs=epochMs;
            this.slots=List.copyOf(slots); this.effects=List.copyOf(effects); this.selected=selected;
            this.hp=hp; this.maxHp=maxHp; this.food=food; this.saturation=saturation;
            this.vehicleType=vehicleType; this.vehicleUUID=vehicleUUID; this.vehicleId=vehicleId; this.task=task;
        }
        public UUID id(){return id;} public String dimension(){return dimension;}
        public long tick(){return tick;} public long epochMs(){return epochMs;}
        public ItemStack slot(int index){return slots.get(index).copy();}
        public List<ItemStack> main(){return java.util.stream.IntStream.range(0,36).mapToObj(this::slot).toList();}
        public List<MobEffectInstance> effects(){return effects.stream().map(MobEffectInstance::new).toList();}
        public int selected(){return selected;} public float hp(){return hp;} public float maxHp(){return maxHp;}
        public int food(){return food;} public float saturation(){return saturation;}
        public String vehicleType(){return vehicleType;} public UUID vehicleUUID(){return vehicleUUID;}
        public int vehicleId(){return vehicleId;} public Task task(){return task;}
    }
}
