package com.dwinovo.numen.api.observation;

/**
 * A sample's provenance, not a promise that the world has stopped changing (2026-11-05).
 * WHY: identical tick/clock numbers from another server session must not form a fake snapshot.
 * Legacy responses deliberately carry UNKNOWN metadata; never infer identity from a consumer label.
 * The source owns writes. This envelope only describes a read projection; server-loaded is not visible.
 */
public record ObservationMetadata(String worldId, String serverSession, String dimension,
                                  String subjectId, String source, String observationKey,
                                  long revision, long observedAtMs, Freshness freshness,
                                  String visibility, long validUntilMs) {
    public enum Freshness { CURRENT, STALE, HISTORICAL, UNKNOWN }

    /** Legacy/unknown expiry is not permission to keep a sample indefinitely. */
    public ObservationMetadata(String worldId, String serverSession, String dimension, String subjectId,
                               String source, String observationKey, long revision, long observedAtMs,
                               Freshness freshness, String visibility) {
        this(worldId, serverSession, dimension, subjectId, source, observationKey, revision, observedAtMs,
                freshness, visibility, 0);
    }

    public ObservationMetadata {
        worldId = clean(worldId);
        serverSession = clean(serverSession);
        dimension = clean(dimension);
        subjectId = clean(subjectId);
        source = clean(source);
        observationKey = clean(observationKey);
        freshness = freshness == null ? Freshness.UNKNOWN : freshness;
        visibility = clean(visibility);
    }

    public static ObservationMetadata unknown() {
        return new ObservationMetadata("", "", "", "", "", "", 0, 0, Freshness.UNKNOWN, "UNKNOWN");
    }

    public boolean identified() {
        return !worldId.isEmpty() && !worldId.startsWith("unknown") && !serverSession.isEmpty()
                && !dimension.isEmpty() && !source.isEmpty() && !observationKey.isEmpty()
                && revision > 0 && observedAtMs > 0;
    }

    public ObservationMetadata withFreshness(Freshness value) {
        return new ObservationMetadata(worldId, serverSession, dimension, subjectId, source,
                observationKey, revision, observedAtMs, value, visibility, validUntilMs);
    }

    private static String clean(String value) { return value == null ? "" : value.trim(); }
}
