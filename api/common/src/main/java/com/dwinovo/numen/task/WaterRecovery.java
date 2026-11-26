package com.dwinovo.numen.task;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Temporary water-stall recovery requests and local route avoidance, owned by the host task API. */
public final class WaterRecovery {
    public static final int RECOVERY_BUDGET_TICKS = 300;
    public static final int AVOID_TTL_TICKS = 1200;
    private static final int AVOID_RADIUS = 4;
    private static final int AVOID_HALF_HEIGHT = 2;
    private static final int MAX_AVOID_ZONES = 4;

    private static final Map<UUID, Request> REQUESTS = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<UUID, List<AvoidZone>> ZONES = Collections.synchronizedMap(new WeakHashMap<>());

    private WaterRecovery() {}

    public enum State { NONE, PENDING, RECOVERING, SUCCEEDED, FAILED }

    public record AvoidZone(BlockPos origin, long expiresAt) {
        public boolean contains(int x, int y, int z) {
            return Math.abs(x - origin.getX()) <= AVOID_RADIUS
                    && Math.abs(z - origin.getZ()) <= AVOID_RADIUS
                    && Math.abs(y - origin.getY()) <= AVOID_HALF_HEIGHT;
        }

        public boolean allowsExit(int fromX, int fromY, int fromZ, int toX, int toY, int toZ) {
            long dx = toX - origin.getX(), dy = toY - origin.getY(), dz = toZ - origin.getZ();
            long fx = fromX - origin.getX(), fy = fromY - origin.getY(), fz = fromZ - origin.getZ();
            return dx * dx + dy * dy + dz * dz > fx * fx + fy * fy + fz * fz;
        }
    }

    private record Request(BlockPos origin, long startedAt, State state) {}

    public static void request(UUID companionId, BlockPos origin, long now) {
        Request current = REQUESTS.get(companionId);
        if (current != null && (current.state() == State.PENDING || current.state() == State.RECOVERING)) {
            return;
        }
        REQUESTS.put(companionId, new Request(origin.immutable(), now, State.PENDING));
    }

    public static boolean requested(UUID companionId) {
        Request current = REQUESTS.get(companionId);
        return current != null && (current.state() == State.PENDING || current.state() == State.RECOVERING);
    }

    public static BlockPos begin(UUID companionId, long now) {
        Request current = REQUESTS.get(companionId);
        if (current == null || current.state() != State.PENDING) return null;
        if (expired(current, now)) {
            REQUESTS.remove(companionId, current);
            avoid(companionId, current.origin(), now);
            REQUESTS.put(companionId, new Request(current.origin(), current.startedAt(), State.FAILED));
            return null;
        }
        Request recovering = new Request(current.origin(), now, State.RECOVERING);
        REQUESTS.put(companionId, recovering);
        return recovering.origin();
    }

    public static boolean succeed(UUID companionId, long now) {
        return finish(companionId, now, State.SUCCEEDED);
    }

    public static boolean fail(UUID companionId, long now) {
        return finish(companionId, now, State.FAILED);
    }

    private static boolean finish(UUID companionId, long now, State outcome) {
        Request current = REQUESTS.remove(companionId);
        if (current == null) return false;
        avoid(companionId, current.origin(), now);
        REQUESTS.put(companionId, new Request(current.origin(), current.startedAt(), outcome));
        return true;
    }

    public static State poll(UUID companionId, long now) {
        Request current = REQUESTS.get(companionId);
        if (current == null) return State.NONE;
        if (current.state() == State.SUCCEEDED || current.state() == State.FAILED) {
            REQUESTS.remove(companionId, current);
            return current.state();
        }
        if (expired(current, now)) {
            REQUESTS.remove(companionId, current);
            avoid(companionId, current.origin(), now);
            return State.FAILED;
        }
        return current.state();
    }

    public static List<AvoidZone> avoidedZones(UUID companionId, long now) {
        if (companionId == null) return List.of();
        List<AvoidZone> zones = ZONES.get(companionId);
        if (zones == null) return List.of();
        synchronized (zones) {
            zones.removeIf(zone -> zone.expiresAt() <= now);
            if (zones.isEmpty()) {
                ZONES.remove(companionId, zones);
                return List.of();
            }
            return List.copyOf(zones);
        }
    }

    public static void clearRequest(UUID companionId) {
        REQUESTS.remove(companionId);
    }

    public static void clear(UUID companionId) {
        REQUESTS.remove(companionId);
        ZONES.remove(companionId);
    }

    private static boolean expired(Request request, long now) {
        return now - request.startedAt() >= RECOVERY_BUDGET_TICKS;
    }

    private static void avoid(UUID companionId, BlockPos origin, long now) {
        List<AvoidZone> zones = ZONES.computeIfAbsent(companionId, ignored -> new ArrayList<>());
        synchronized (zones) {
            zones.removeIf(zone -> zone.expiresAt() <= now);
            if (zones.size() >= MAX_AVOID_ZONES) {
                zones.remove(0);
            }
            zones.add(new AvoidZone(origin.immutable(), now + AVOID_TTL_TICKS));
        }
    }
}
