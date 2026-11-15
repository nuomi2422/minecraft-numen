package com.dwinovo.numen.entity;

import java.util.Map;
import java.util.function.Supplier;

/** Detached event projection; candidate is not necessarily the body returned after SPAWN callbacks. */
final class RespawnEventSnapshot {
    record Landing(String dimension, double x, double y, double z) {
        String pos() { return x + "," + y + "," + z; }
    }

    private RespawnEventSnapshot() {}

    // Read after Factory.spawn has completed every SPAWN callback; this never moves the body.
    static Map<String, String> attributes(Landing candidate, String candidateReason,
                                          Supplier<Landing> finalBody) {
        Landing actual = finalBody.get();
        String reason = candidate.equals(actual) ? candidateReason : "spawn_callback_relocated";
        return Map.of("dimension", actual.dimension(), "pos", actual.pos(), "reason", reason,
                "candidate_dimension", candidate.dimension(), "candidate_pos", candidate.pos(),
                "candidate_reason", candidateReason);
    }
}
