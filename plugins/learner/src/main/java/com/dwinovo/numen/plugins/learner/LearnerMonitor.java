package com.dwinovo.numen.plugins.learner;

import com.dwinovo.numen.api.NumenPlugins;
import java.util.Map;

/**
 * 2026-11-14: legacy learner observations use the host's single bounded journal.
 * WHY: the private FML writer omitted the shared session/sequence and could silently disappear.
 * Two-argument callers have no captured identity, so remain UNKNOWN even with a current world.
 * No private-file fallback: acceptance, persistence and delivery are separate facts.
 */
final class LearnerMonitor {
    private LearnerMonitor() {}

    static void publish(String type, Map<String, ?> data) {
        try {
            NumenPlugins.tryPublishMonitor("learner", type, data, null);
        } catch (RuntimeException | LinkageError unavailable) {
            // This observation side channel must not interrupt review, restore, or queue operations.
        }
    }
}
