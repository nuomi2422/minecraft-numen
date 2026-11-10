package com.dwinovo.numen.api;

import java.util.Objects;
import java.util.UUID;

/**
 * R-06: injectable scheduling seam for enqueue receipts.
 *
 * <p>Production wraps Minecraft (server-thread capture + client thread execution);
 * tests inject a scripted fake to exercise identity/connection/target changes
 * without a live game. This is a seam over existing calls, not a second source of truth.
 *
 * <p>epoch/refs are only a local connection generation, never world authority.
 */
public interface EnqueueScheduling {

    /**
     * Immutable subject snapshot: identity strings, local connection generation,
     * and the concrete entity/level/server objects the sample was taken from.
     * Object refs let callers reject "same UUID, new entity" instead of only comparing UUIDs.
     */
    record Sample(String worldId, String serverSession, long epoch, String connectionId,
                  Object entityRef, Object levelRef, Object serverRef) {
        public boolean hasIdentity() {
            return worldId != null && !worldId.isBlank()
                    && serverSession != null && !serverSession.isBlank();
        }

        public boolean sameSubject(Sample other) {
            if (other == null) return false;
            return epoch == other.epoch
                    && Objects.equals(worldId, other.worldId)
                    && Objects.equals(serverSession, other.serverSession)
                    && Objects.equals(connectionId, other.connectionId);
        }
    }

    /** Non-blocking sample for this companion's current subject. */
    Sample sampleNow(UUID companion);

    /** True when the caller is on the client thread (where execution must happen). */
    boolean onClientThread();

    /** Run a task on the client thread. */
    void executeOnClient(Runnable task);

    /** Live entity UUID for this companion in the current instance, or null if absent. */
    UUID liveTargetUuid(UUID companion);
}
