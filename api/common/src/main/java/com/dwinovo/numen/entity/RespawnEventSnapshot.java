package com.dwinovo.numen.entity;

import java.util.Map;
import java.util.function.Supplier;

/** Detached event projection; candidate is not necessarily the body returned after SPAWN callbacks. */
final class RespawnEventSnapshot {
    record Landing(String dimension, double x, double y, double z) {
        String pos() { return x + "," + y + "," + z; }
    }

    private RespawnEventSnapshot() {}

    // Test-first extraction of the existing projection; no body relocation or spawn policy here.
    static Map<String, String> attributes(Landing candidate, String candidateReason,
                                          Supplier<Landing> finalBody) {
        return Map.of("dimension", candidate.dimension(), "pos", candidate.pos(), "reason", "owner_nearby");
    }
}
