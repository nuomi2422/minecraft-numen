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
                case "environment" -> v.get("day_time") instanceof Number && v.get("weather") instanceof String
                        && request.dimension().equals(v.get("dimension"));
                case "entities" -> v.get("count") instanceof Number;
                case "blocks" -> v.get("block") instanceof String && v.get("count") instanceof Number;
                case "containers" -> v.get("item") instanceof String && v.get("count") instanceof Number;
                default -> false;
            };
        });
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
            String container = original.selector().getOrDefault("container", "");
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
            long ageLimit = query.requests().get(i).maxAgeMs();
            if (m.observedAtMs() <= 0 || m.observedAtMs() > deliveredAt
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
