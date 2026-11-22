package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.rdd.side.SideTaskWorldPort;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每服务端 tick 记录"睡着→醒"边沿（{@link SideTaskWorldPort.Wake}）。
 *
 * <p>支线引擎每 20 tick 才跑，而原版"条件满足即跳夜"可能让 {@code isSleeping()} 只维持
 * 一两 tick —— 按 20 tick 采样会整夜漏判。所以这里按 tick 抓边沿，引擎来取。
 *
 * <p>只读 {@code isSleeping()}，用独立状态表；<b>不碰</b> {@link NumenPlayer#pollWokeUp()}
 * 的内部记账（那是内置大脑在消费的，抢它会偷走主线的醒来事件）。
 */
final class SleepWakeWatcher {

    private static final Map<UUID, Boolean> LAST_SLEEPING = new ConcurrentHashMap<>();
    private static final Map<UUID, SideTaskWorldPort.Wake> PENDING = new ConcurrentHashMap<>();
    private static boolean installed;
    private record CycleScope(net.minecraft.server.MinecraftServer server, NumenPlayer body,
                              com.dwinovo.numen.api.WorldContextStamp.Token world, String dimension,
                              UUID companionId, String instanceId, long generation, long createdAt) {}
    private static final class Cycle {
        final CycleScope scope;
        boolean sleeping;
        long lastTick;
        long lastDayTime;
        long sequence;
        long startedTick = -1;
        long startedDayTime = -1;
        com.dwinovo.numen.rdd.side.SleepCycleEvidence terminal;
        Cycle(CycleScope scope, boolean sleeping, long tick, long dayTime) {
            this.scope = scope; this.sleeping = sleeping; this.lastTick = tick; this.lastDayTime = dayTime;
        }
    }
    private static final Map<UUID, Cycle> CYCLES = new ConcurrentHashMap<>();

    private SleepWakeWatcher() {
    }

    static void install() {
        if (installed) {
            return;
        }
        installed = true;
        NeoForge.EVENT_BUS.addListener(SleepWakeWatcher::onServerTick);
    }

    private static void onServerTick(ServerTickEvent.Pre event) {
        if (event.getServer() == null) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            if (!(player instanceof NumenPlayer ap)) {
                continue;
            }
            sampleCycle(ap, event.getServer());
            boolean now = ap.isSleeping();
            Boolean previous = LAST_SLEEPING.put(ap.getUUID(), now);
            if (previous != null && previous && !now) {
                PENDING.put(ap.getUUID(), ap.level().isDay()
                        ? SideTaskWorldPort.Wake.DAYBREAK
                        : SideTaskWorldPort.Wake.INTERRUPTED);
            }
        }
        CYCLES.entrySet().removeIf(entry -> entry.getValue().scope.server() == event.getServer()
                && !event.getServer().getPlayerList().getPlayers().contains(entry.getValue().scope.body()));
    }

    /** 消费一次醒来的边沿；没有则为 {@link SideTaskWorldPort.Wake#NONE}。 */
    static SideTaskWorldPort.Wake poll(UUID companionId) {
        SideTaskWorldPort.Wake wake = PENDING.remove(companionId);
        return wake == null ? SideTaskWorldPort.Wake.NONE : wake;
    }

    private static CycleScope scope(NumenPlayer body, com.dwinovo.numen.rdd.side.SideTask expected) {
        if (body == null || expected == null || !body.isAlive() || body.isRemoved()
                || !body.getUUID().equals(expected.companionId())
                || !com.dwinovo.numen.rdd.side.sleep.SleepSideTaskType.isOvernight(expected)
                || expected.terminal() || body.getServer() == null || !body.getServer().isSameThread()) return null;
        var owner = SideTaskHost.activeSleepOwner(body.getUUID());
        if (owner == null || owner.terminal() || !owner.instanceId().equals(expected.instanceId())
                || owner.generation() != expected.generation()) return null;
        var world = com.dwinovo.numen.api.WorldContextStamp.capture(body.getServer());
        if (world == null || world.identity() == null
                || world.identity().getOrDefault("world_id", "").isBlank()
                || world.identity().getOrDefault("server_session", "").isBlank()) return null;
        return new CycleScope(body.getServer(), body, world, body.level().dimension().location().toString(),
                body.getUUID(), expected.instanceId(), expected.generation(), expected.createdAtGameTime());
    }
    private static void sampleCycle(NumenPlayer body, net.minecraft.server.MinecraftServer server) {
        UUID id = body.getUUID();
        Cycle captured = CYCLES.get(id);
        long capturedSequence = captured == null ? -1 : captured.sequence;
        try {
            var owner = SideTaskHost.activeSleepOwner(id);
            CycleScope scope = scope(body, owner);
            if (scope == null || scope.server() != server) {
                if (captured != null && captured.scope.body() == body) invalidate(id, captured, capturedSequence);
                return;
            }
            long tick = body.level().getGameTime(), day = body.level().getDayTime();
            boolean sleeping = body.isSleeping();
            Cycle cycle = CYCLES.get(id);
            if (cycle == null || !cycle.scope.equals(scope) || tick < cycle.lastTick
                    || tick - cycle.lastTick > 2 || day < cycle.lastDayTime) {
                var replacement = new Cycle(scope, sleeping, tick, day);
                if (cycle == null) CYCLES.putIfAbsent(id, replacement); else CYCLES.replace(id, cycle, replacement);
                return; // First observation (even true) cannot establish a start.
            }
            if (tick == cycle.lastTick) return;
            if (!cycle.sleeping && sleeping) {
                long time = Math.floorMod(day, 24_000L);
                cycle.startedTick = time >= 13_000 && time < 23_000 && tick >= scope.createdAt() ? tick : -1;
                cycle.startedDayTime = cycle.startedTick >= 0 ? day : -1;
                cycle.terminal = null;
                if (cycle.startedTick >= 0) cycle.sequence++;
            } else if (cycle.sleeping && !sleeping && cycle.startedTick >= 0) {
                var status = body.level().isDay()
                        ? com.dwinovo.numen.rdd.side.SleepCycleEvidence.Status.DAYBREAK
                        : com.dwinovo.numen.rdd.side.SleepCycleEvidence.Status.INTERRUPTED;
                cycle.terminal = evidence(cycle, status, tick, day);
                cycle.startedTick = -1;
            }
            cycle.sleeping = sleeping;
            cycle.lastTick = tick;
            cycle.lastDayTime = day;
        } catch (RuntimeException unavailable) {
            if (captured != null && captured.scope.body() == body) invalidate(id, captured, capturedSequence);
        }
    }
    private static com.dwinovo.numen.rdd.side.SleepCycleEvidence evidence(Cycle cycle,
            com.dwinovo.numen.rdd.side.SleepCycleEvidence.Status status, long tick, long day) {
        var s = cycle.scope;
        return new com.dwinovo.numen.rdd.side.SleepCycleEvidence(status, s.companionId(),
                s.world().identity().get("world_id"), s.world().identity().get("server_session"), s.dimension(),
                s.instanceId(), s.generation(), cycle.sequence, cycle.startedTick, tick, cycle.startedDayTime, day);
    }
    static com.dwinovo.numen.rdd.side.SleepCycleEvidence poll(NumenPlayer body, com.dwinovo.numen.rdd.side.SideTask expected) {
        var unknown = com.dwinovo.numen.rdd.side.SleepCycleEvidence.UNKNOWN;
        if (body == null || expected == null || !body.getUUID().equals(expected.companionId())) return unknown;
        UUID id = body.getUUID();
        Cycle cycle = CYCLES.get(id);
        long sequence = cycle == null ? -1 : cycle.sequence;
        // A stale reader has no cleanup authority over the current owner's state.
        if (cycle == null || cycle.scope.body() != body
                || !cycle.scope.instanceId().equals(expected.instanceId())
                || cycle.scope.generation() != expected.generation()) return unknown;
        try {
            var owner = SideTaskHost.activeSleepOwner(id);
            if (owner == null || !owner.instanceId().equals(expected.instanceId())
                    || owner.generation() != expected.generation()) return unknown;
            CycleScope current = scope(body, expected);
            long tick = body.level().getGameTime();
            long day = body.level().getDayTime();
            if (current == null || !cycle.scope.equals(current) || tick < cycle.lastTick
                    || tick - cycle.lastTick > 2 || day < cycle.lastDayTime) {
                invalidate(id, cycle, sequence);
                return unknown;
            }
            if (CYCLES.get(id) != cycle || cycle.sequence != sequence) return unknown;
            if (cycle.terminal != null) {
                var result = cycle.terminal; cycle.terminal = null;
                return result.matches(expected) ? result : unknown;
            }
            return cycle.sleeping && cycle.startedTick >= 0
                    ? evidence(cycle, com.dwinovo.numen.rdd.side.SleepCycleEvidence.Status.OBSERVED_SLEEP, tick, day)
                    : unknown;
        } catch (RuntimeException unavailable) {
            invalidate(id, cycle, sequence);
            return unknown;
        }
    }
    private static void invalidate(UUID id, Cycle captured, long sequence) {
        if (captured == null) return;
        CYCLES.computeIfPresent(id, (key, current) -> current == captured && current.sequence == sequence ? null : current);
    }
    /** Exact scope cleanup: late old-owner/body cleanup never clears a newer cycle. */
    static void clear(NumenPlayer body, com.dwinovo.numen.rdd.side.SideTask expected) {
        if (body == null || expected == null) return;
        clear(body.getUUID(), expected.instanceId(), expected.generation(), body);
    }
    static void clear(UUID id, String instance, long generation, NumenPlayer body) {
        Cycle cycle = CYCLES.get(id);
        if (cycle != null && cycle.scope.body() == body && cycle.scope.instanceId().equals(instance)
                && cycle.scope.generation() == generation) invalidate(id, cycle, cycle.sequence);
    }
    static void clearBody(NumenPlayer body) {
        if (body == null) return;
        Cycle cycle = CYCLES.get(body.getUUID());
        if (cycle != null && cycle.scope.body() == body) invalidate(body.getUUID(), cycle, cycle.sequence);
    }

    static void clearAll() {
        LAST_SLEEPING.clear();
        PENDING.clear();
        CYCLES.clear();
    }
}
