package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.ObservationMetadata;
import com.dwinovo.numen.api.observation.WorldObservationService.*;
import com.dwinovo.numen.worldstate.core.query.ReadResult;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * One mapping for provenance and caller budgets (039/B1, 2026-11-05).
 * WHY: topic TTL alone ignores a task's stricter age limit; stale empty lists have no item to mark.
 * Metadata carries freshness even for empty containers. Truncation never proves absence.
 * Pure code: tests exercise the mapper used by the running capability, not a second oracle.
 */
public final class ObservationResponses {
    private ObservationResponses() {}

    private static long validUntil(long sampledAt, long topicTtl, long requestedAge) {
        if (sampledAt <= 0 || topicTtl < 0) return 0;
        long ttl = requestedAge > 0 ? Math.min(topicTtl, requestedAge) : topicTtl;
        return sampledAt > Long.MAX_VALUE - ttl ? Long.MAX_VALUE : sampledAt + ttl;
    }

    public static Response map(ReadResult rr, Request request, List<Item> items, long watermark, long nowMs,
                               com.dwinovo.numen.worldstate.core.observation.ObservationKey expectedKey, long topicTtlMs) {
        if (rr == null || !rr.present())
            return new Response(Status.PENDING, request.topic(), "", 0, Coverage.UNKNOWN,
                    "no observation yet; refresh explicitly", List.of(), watermark);
        var o = rr.observation();
        if (expectedKey == null || !expectedKey.equals(o.key()))
            return Response.error(request.topic(), "observation key does not match requested world/target/filter");
        var f = ObservationMetadata.Freshness.valueOf(rr.freshness().name());
        if (o.epochMs() <= 0 || o.epochMs() > nowMs) f = ObservationMetadata.Freshness.UNKNOWN;
        else if (f == ObservationMetadata.Freshness.CURRENT && request.maxAgeMs() > 0
                && nowMs - o.epochMs() > request.maxAgeMs()) f = ObservationMetadata.Freshness.STALE;
        String target = o.key().target();
        String subject = target.startsWith("player:") ? target.substring(7) : "";
        var metadata = new ObservationMetadata(o.key().worldId(), o.serverSession(), o.key().dimension(),
                subject, o.source(), o.key().canonical(), o.revision(), o.epochMs(), f, "SERVER_LOADED",
                validUntil(o.epochMs(), topicTtlMs, request.maxAgeMs()));
        var basic = ObservationMap.map(rr.coverage(), rr.freshness(), true);
        boolean stale = f != ObservationMetadata.Freshness.CURRENT;
        int limit = request.maxItems() <= 0 ? 64 : Math.min(256, request.maxItems());
        boolean truncated = items.size() > limit;
        List<Item> bounded = items.stream().limit(limit).map(i -> stale
                ? new Item(i.values(), i.unknownFields(), i.values().keySet()) : i).toList();
        Status status = basic.status();
        String reason = basic.reason();
        Coverage coverage = basic.coverage();
        if (stale) { status = Status.PARTIAL; reason = "freshness=" + f; }
        if (truncated) {
            status = Status.PARTIAL; coverage = Coverage.PARTIAL;
            reason = (reason == null ? "" : reason + "; ") + "maxItems truncated " + items.size() + " to " + limit;
        }
        String keyId = UUID.nameUUIDFromBytes(o.key().canonical().getBytes(StandardCharsets.UTF_8)).toString();
        String snapshot = o.serverSession() + ":" + keyId + ":" + o.revision();
        return new Response(status, request.topic(), snapshot, o.gameTick(), coverage, reason, bounded, watermark, metadata);
    }
}
