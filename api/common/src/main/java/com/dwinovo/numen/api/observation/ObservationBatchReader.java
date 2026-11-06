package com.dwinovo.numen.api.observation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import static com.dwinovo.numen.api.observation.WorldObservationService.*;
import static com.dwinovo.numen.api.observation.ObservationBatch.*;

/**
 * Shared composition policy used by the live capability and offline contract tests (2026-11-05).
 * WHY: checking only a batch's outer OK admits old empty containers and mixed-session samples.
 * This calls read only, with cooperative deadlines. It neither scans nor claims to preempt a provider.
 */
public final class ObservationBatchReader {
    private ObservationBatchReader() {}

    /** Four wired topic contracts. A new topic must define its shape before strict batch admission. */
    private static boolean shapeKnown(Request request, Response response) {
        if (response.items().isEmpty()) return "containers".equals(request.topic());
        return response.items().stream().allMatch(item -> {
            var v = item.values();
            return switch (request.topic()) {
                case "self" -> selfShape(request, response, v);
                case "environment" -> v.get("day_time") instanceof Number && v.get("weather") instanceof String
                        && request.dimension().equals(v.get("dimension"));
                case "entities" -> v.get("count") instanceof Number;
                case "blocks" -> v.get("block") instanceof String && v.get("count") instanceof Number;
                case "containers" -> v.get("item") instanceof String && v.get("count") instanceof Number;
                default -> false;
            };
        });
    }

    /** SELF is one complete detached subject, not an empty inventory or a partial summary. */
    private static boolean selfShape(Request request, Response response, java.util.Map<String, Object> v) {
        String target = request.selector().getOrDefault("target", "");
        if (response.items().size() != 1 || !target.startsWith("player:")
                || !target.substring(7).equals(v.get("companionUUID")) || !request.dimension().equals(v.get("dimension"))) return false;
        for (String key : List.of("hp", "maxHp", "food", "saturation", "selected"))
            if (!(v.get(key) instanceof Number)) return false;
        int selected = ((Number) v.get("selected")).intValue();
        if (selected < 0 || selected > 8 || !selfSlots(v.get("main"), 0, 36)
                || !selfSlots(v.get("armor"), 36, 4) || !selfSlot(v.get("offhand"), 40)) return false;
        if (!(v.get("mainHand") instanceof java.util.Map<?, ?> hand)
                || !selfSlot(hand, selected) || !hand.equals(((List<?>) v.get("main")).get(selected))) return false;
        if (!(v.get("effects") instanceof List<?> effects)) return false;
        for (Object effect : effects) {
            if (!(effect instanceof java.util.Map<?, ?> e) || !(e.get("id") instanceof String)
                    || !(e.get("amplifier") instanceof Number) || !(e.get("infinite") instanceof Boolean)) return false;
            if (!Boolean.TRUE.equals(e.get("infinite"))
                    && (!(e.get("observedDuration") instanceof Number) || !(e.get("expiryTick") instanceof Number))) return false;
        }
        if (!(v.get("vehicle") instanceof java.util.Map<?, ?> vehicle) || !(vehicle.get("type") instanceof String type)
                || (!type.isEmpty() && !(vehicle.get("uuid") instanceof String))) return false;
        return v.get("bodyTask") instanceof java.util.Map<?, ?> task && task.get("taskId") instanceof String
                && task.get("tool") instanceof String && task.get("describe") instanceof String
                && task.get("standing") instanceof Boolean && task.get("elapsedMs") instanceof Number;
    }

    private static boolean selfSlots(Object value, int start, int count) {
        if (!(value instanceof List<?> slots) || slots.size() != count) return false;
        for (int i = 0; i < count; i++) if (!selfSlot(slots.get(i), start + i)) return false;
        return true;
    }

    private static boolean selfSlot(Object value, int index) {
        return value instanceof java.util.Map<?, ?> slot && slot.get("slot") instanceof Number n
                && n.doubleValue() == index && slot.get("id") instanceof String && slot.get("count") instanceof Number
                && slot.get("damage") instanceof Number && slot.get("maxDamage") instanceof Number
                && slot.get("isDamageable") instanceof Boolean;
    }

    public static Result read(WorldObservationService service, Query query) {
        return read(service, query, System::currentTimeMillis, System::nanoTime);
    }

    public static Result read(WorldObservationService service, Query query,
                              LongSupplier nowMs, LongSupplier nanoTime) {
        if (service == null || query == null) return Result.error("", "missing service or query");
        List<Response> out = new ArrayList<>();
        long start = nanoTime.getAsLong();
        long budget = (query.deadlineMs() == 0 ? 50 : query.deadlineMs()) * 1_000_000L;
        String world = query.expectedWorldId(), session = query.expectedServerSession();
        long min = Long.MAX_VALUE, max = Long.MIN_VALUE, firstTick = -1;
        boolean sameTick = true, usable = true;
        String problem = "";
        for (Request original : query.requests()) {
            if (nanoTime.getAsLong() - start >= budget)
                return new Result(query.requestId(), Status.PARTIAL, Consistency.UNKNOWN, -1, out, "deadline exceeded; unread entries omitted");
            Request bounded = new Request(original.consumer(), original.dimension(), original.topic(), original.selector(),
                    original.maxAgeMs(), original.maxItems() <= 0 ? 64 : Math.min(MAX_ITEMS_PER_READ, original.maxItems()),
                    Math.min(original.deadlineMs() <= 0 ? 50 : original.deadlineMs(), MAX_DEADLINE_MS));
            Response r;
            try { r = service.read(bounded); }
            catch (RuntimeException ex) { r = Response.error(original.topic(), "provider threw: " + ex.getClass().getSimpleName()); }
            if (r == null) r = Response.error(original.topic(), "provider returned null");
            out.add(r);
            var m = r.metadata();
            String container = original.selector().getOrDefault("self".equals(original.topic()) ? "target" : "container", "");
            String expectedSubject = container.startsWith("player:") ? container.substring(7) : "";
            if (!m.identified() || r.observedTick() < 0 || !expectedSubject.equals(m.subjectId())
                    || !r.topic().equals(original.topic()) || !m.dimension().equals(original.dimension())) {
                usable = false; problem = "missing or mismatched sample identity"; continue;
            }
            if (world.isEmpty()) world = m.worldId();
            if (session.isEmpty()) session = m.serverSession();
            if (!world.equals(m.worldId()) || !session.equals(m.serverSession()))
                return new Result(query.requestId(), Status.PARTIAL, Consistency.NOT_COHERENT, -1, out, "world/session mismatch");
            long now = nowMs.getAsLong();
            boolean old = m.observedAtMs() > now || m.freshness() != ObservationMetadata.Freshness.CURRENT
                    || (original.maxAgeMs() > 0 && now - m.observedAtMs() > original.maxAgeMs());
            if (r.status() != Status.OK || r.coverage() != Coverage.FULL || old || !shapeKnown(original, r)
                    || r.items().stream().anyMatch(i -> !i.unknownFields().isEmpty() || !i.staleFields().isEmpty())) {
                usable = false; problem = "incomplete, unknown or stale sample";
            }
            min = Math.min(min, m.observedAtMs()); max = Math.max(max, m.observedAtMs());
            if (firstTick < 0) firstTick = r.observedTick();
            else sameTick &= firstTick == r.observedTick();
        }
        if (nanoTime.getAsLong() - start >= budget)
            return new Result(query.requestId(), Status.PARTIAL, Consistency.UNKNOWN, -1, out, "deadline exceeded after read");
        // A first read may age out while the later reads finish. Judge ALL samples at delivery time.
        long deliveredAt = nowMs.getAsLong();
        for (int i = 0; i < out.size(); i++) {
            var m = out.get(i).metadata();
            // 2026-11-06 B-249: a missing sample is not an expired observation.
            // Keep its identity diagnostic, while still checking every dated sample below.
            if (m.observedAtMs() <= 0) continue;
            long ageLimit = query.requests().get(i).maxAgeMs();
            if (m.observedAtMs() > deliveredAt
                    || m.validUntilMs() < m.observedAtMs() || deliveredAt > m.validUntilMs()
                    || (ageLimit > 0 && deliveredAt - m.observedAtMs() > ageLimit)) {
                usable = false; problem = "sample expired before batch delivery";
            }
        }
        if (!usable) return new Result(query.requestId(), Status.PARTIAL, Consistency.UNKNOWN, -1, out, problem);
        long skew = max - min;
        if (skew > query.maxSkewMs())
            return new Result(query.requestId(), Status.PARTIAL, Consistency.NOT_COHERENT, skew, out, "sampling skew exceeds tolerance");
        return new Result(query.requestId(), Status.OK, sameTick ? Consistency.SAME_TICK : Consistency.BOUNDED_SKEW,
                skew, out, "cached observations; not an atomic world transaction or a completed task");
    }
}
