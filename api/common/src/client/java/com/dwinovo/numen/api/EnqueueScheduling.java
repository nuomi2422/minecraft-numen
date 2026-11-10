package com.dwinovo.numen.api;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * R-06: injectable scheduling seam for enqueue receipts.
 *
 * <p>Production captures identity on the concrete server thread (server.execute) and
 * executes on the client thread. epoch/refs are only a local generation, never world authority.
 */
public interface EnqueueScheduling {

    /**
     * Immutable subject snapshot: identity strings, local connection generation,
     * and the concrete entity/level/server objects the sample was taken from.
     */
    record Sample(String worldId, String serverSession, long epoch, String connectionId,
                  Object entityRef, Object levelRef, Object serverRef) {
        public boolean hasIdentity() {
            return worldId != null && !worldId.isBlank()
                    && serverSession != null && !serverSession.isBlank();
        }

        /** Complete binding needed before dispatch; any missing ref means "not ready". */
        public boolean ready() {
            return hasIdentity() && entityRef != null && levelRef != null && serverRef != null;
        }

        public boolean sameSubject(Sample other) {
            if (other == null) return false;
            return epoch == other.epoch
                    && Objects.equals(worldId, other.worldId)
                    && Objects.equals(serverSession, other.serverSession)
                    && Objects.equals(connectionId, other.connectionId)
                    && entityRef == other.entityRef
                    && levelRef == other.levelRef
                    && serverRef == other.serverRef;
        }

        public static Sample missing() {
            return new Sample(null, null, 0L, null, null, null, null);
        }
    }

    /** Non-blocking client-side sample (best effort; empty when not on the server thread). */
    Sample sampleNow(UUID companion);

    /** Captured on the concrete server thread, non-blocking for the caller. */
    default CompletableFuture<Sample> sampleAsync(UUID companion) {
        return CompletableFuture.completedFuture(sampleNow(companion));
    }

    /** True when the caller is on the client thread (where execution must happen). */
    boolean onClientThread();

    /** Run a task on the client thread. */
    void executeOnClient(Runnable task);

    /** Live entity UUID for this companion in the current instance, or null if absent. */
    UUID liveTargetUuid(UUID companion);
}
