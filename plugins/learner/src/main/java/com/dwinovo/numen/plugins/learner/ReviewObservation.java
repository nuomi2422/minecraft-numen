package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.WorldContextStamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 2026-11-14: one review's immutable producer token for lifecycle observation only.
 * WHY: the red journal tests found missing lifecycle rows after a real queue barrier.
 * Compare with the supplied current token before enqueue; never borrow a later world.
 * No world registry or permission gate: rejection must not stop review or restoration.
 */
record ReviewObservation(WorldContextStamp.Token start) {
    ReviewObservation {
        if (start != null) {
            start = new WorldContextStamp.Token(start.identity() == null ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(start.identity())), start.epoch());
        }
    }

    boolean publish(String type, Map<String, ?> data, WorldContextStamp.Token current) {
        if (!known(start) || !known(current) || !start.equals(current)) return false;
        try {
            // ACK is queue acceptance only. A later writer conflict keeps this original start envelope.
            return NumenPlugins.tryPublishMonitor("learner", type, data, start);
        } catch (RuntimeException | LinkageError unavailable) {
            return false; // Observation failure does not change business state; fatal errors still escape.
        }
    }

    private static boolean known(WorldContextStamp.Token token) {
        return token != null && token.epoch() > 0 && token.identity() != null
                && knownId(token.identity().get("world_id")) && knownId(token.identity().get("server_session"));
    }

    private static boolean knownId(String id) {
        if (id == null || id.isBlank()) return false;
        String normalized = id.strip().toLowerCase(Locale.ROOT);
        return !normalized.equals("unknown") && !normalized.startsWith("unknown-");
    }
}
