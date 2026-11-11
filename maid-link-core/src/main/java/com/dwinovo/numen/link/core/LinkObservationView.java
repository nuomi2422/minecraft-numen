package com.dwinovo.numen.link.core;

import java.util.Locale;
import java.util.Objects;
import com.dwinovo.numen.worldstate.core.model.*;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.query.ReadResult;
import static com.dwinovo.numen.link.core.LinkTypes.*;

/**
 * 052/P3, 2026-11-11: projection of the existing world-state-core cached ReadResult.
 * No parallel observation schema/store, read supplier, refresh or world access.
 * Host authenticates owner/audience and authorizes this exact key before each projection.
 * Same world/session/key and bounded age are required for current-state presentation.
 * This is observation data, never proof of possession or task success; experience/assets
 * need their own inspected adapters. Current consumer is an offline fixture only.
 */
public final class LinkObservationView {
    private LinkObservationView() {}
    public record View(Check check, ReadResult source, boolean currentComplete) {}
    public static View project(Binding binding, ObservationKey expectedKey, Check authorization,
                               ReadResult cached, long nowMs, long maxAgeMs) {
        Objects.requireNonNull(binding); Objects.requireNonNull(expectedKey);
        Objects.requireNonNull(authorization);
        if (nowMs < 0 || maxAgeMs <= 0) throw new IllegalArgumentException("bounded-read-age-required");
        if (!authorization.ok()) return new View(authorization, null, false);
        if (!binding.world().trim().toLowerCase(Locale.ROOT).equals(expectedKey.worldId()))
            return new View(new Check(Code.DENIED, "binding-world-mismatch"), null, false);
        if (cached == null) return new View(new Check(Code.UNAVAILABLE, "cached-provider-unavailable"), null, false);
        if (!expectedKey.equals(cached.key()))
            return new View(new Check(Code.DENIED, "query-key-mismatch"), null, false);
        if (!cached.present() || cached.observation() == null)
            return new View(new Check(Code.UNKNOWN, "no-observation"), cached, false);
        var observation = cached.observation();
        if (!expectedKey.equals(observation.key()) || !binding.session().equals(observation.serverSession()))
            return new View(new Check(Code.STALE, "observation-session-or-key"), null, false);
        if (observation.payload() == null || observation.payload().topic() != expectedKey.topic()
                || observation.source() == null || observation.source().isBlank()
                || observation.revision() <= 0 || observation.dataVersion() <= 0)
            return new View(new Check(Code.UNKNOWN, "incomplete-provenance"), null, false);
        boolean current = cached.reason() == FailReason.NONE && cached.freshness() == Freshness.CURRENT
                && observation.freshness() == Freshness.CURRENT && observation.epochMs() > 0
                && observation.epochMs() <= nowMs && nowMs - observation.epochMs() <= maxAgeMs
                && cached.ageMillis() >= 0 && cached.ageMillis() <= maxAgeMs;
        boolean complete = current && cached.coverage() == Coverage.COMPLETE
                && observation.coverage() == Coverage.COMPLETE;
        return new View(new Check(complete ? Code.OK : Code.UNKNOWN,
                complete ? "cached-observation-only" : "stale-partial-or-unknown-observation"), cached, complete);
    }
}
