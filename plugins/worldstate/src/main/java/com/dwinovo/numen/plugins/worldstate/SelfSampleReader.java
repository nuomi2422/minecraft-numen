package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.ObservationMetadata;
import com.dwinovo.numen.api.observation.WorldObservationService.*;
import com.dwinovo.numen.worldstate.core.freshness.TopicFreshnessPolicy;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.payload.SelfPayload;
import com.dwinovo.numen.worldstate.core.query.ReadResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure selection over the existing store snapshot (2026-11-06, B2b).
 * WHY: UUID consumers must not guess dimensions or inspect a live body.
 * Ambiguity fails closed; a sampled dimension is not instantaneous position.
 */
final class SelfSampleReader {
    private SelfSampleReader() {}

    static Response read(List<Observation> samples, TopicFreshnessPolicy freshness,
                         String world, String session, String consumer, UUID subject,
                         long maxAgeMs, long watermark, long now) {
        if (subject == null || world == null || world.isBlank() || world.startsWith("unknown")
                || session == null || session.isBlank() || "unknown".equalsIgnoreCase(session))
            return Response.error("self", "known host world/session and subject required");
        Observation selected = null;
        for (Observation sample : samples) {
            var key = sample.key();
            if (key.topic() != Topic.SELF || !world.equals(key.worldId())
                    || !session.equals(sample.serverSession())
                    || !("player:" + subject).equals(key.target())) continue;
            if (selected != null) return Response.error("self", "ambiguous cached self dimensions/keys");
            selected = sample;
        }
        if (selected == null)
            return new Response(Status.PENDING, "self", "", 0, Coverage.UNKNOWN,
                    "no self sample in current host world/session", List.of(), watermark);
        if (!(selected.payload() instanceof SelfPayload self)
                || !subject.equals(self.companionUUID())
                || !selected.key().dimension().equals(self.dimension())
                || !selected.key().filter().isEmpty())
            return Response.error("self", "cached self identity mismatch");
        Request request = new Request(consumer, self.dimension(), "self",
                Map.of("target", "player:" + subject), maxAgeMs, 1, 0);
        var classified = selected.freshness() == null || selected.freshness() == com.dwinovo.numen.worldstate.core.model.Freshness.UNKNOWN
                ? com.dwinovo.numen.worldstate.core.model.Freshness.UNKNOWN : freshness.classify(selected, now);
        var rr = ReadResult.present(selected, classified, selected.ageMillis(now),
                selected.coverage(), "cached self sample");
        Response mapped = ObservationResponses.map(rr, request,
                List.of(SelfObservationProjection.item(self)), watermark, now,
                selected.key(), freshness.ttlOf(Topic.SELF));
        if (mapped.status() != Status.OK || mapped.coverage() != Coverage.FULL
                || !mapped.metadata().identified()
                || mapped.metadata().freshness() != ObservationMetadata.Freshness.CURRENT
                || mapped.metadata().validUntilMs() <= now) {
            return new Response(Status.PARTIAL, "self", mapped.snapshotId(), mapped.observedTick(),
                    mapped.coverage(), "self sample unavailable as current: " + mapped.metadata().freshness(),
                    List.of(), watermark, mapped.metadata());
        }
        return mapped;
    }
}
