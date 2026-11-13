package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.WorldContextStamp;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 2026-11-14: red-contract ABI for one review's producer-captured observation token.
 * WHY: reviewStart already crosses async work, but lifecycle events still use the old writer.
 * This freezes that token only; it is not a review permission gate or a second world registry.
 * The red stub forwards unchanged legacy publication and cannot claim a shared-queue ACK.
 */
record ReviewObservation(WorldContextStamp.Token start) {
    ReviewObservation {
        if (start != null) {
            start = new WorldContextStamp.Token(start.identity() == null ? null
                    : Collections.unmodifiableMap(new LinkedHashMap<>(start.identity())), start.epoch());
        }
    }

    boolean publish(String type, Map<String, ?> data, WorldContextStamp.Token current) {
        // Red ABI only: no scope guard, shared sink, current-token capture or business decision.
        LearnerMonitor.publish(type, data);
        return false; // Legacy void writer supplies no shared-journal queue acknowledgement.
    }
}
