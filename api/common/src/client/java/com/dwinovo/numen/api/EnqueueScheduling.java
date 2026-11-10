package com.dwinovo.numen.api;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * R-08: two-queue scheduling seam.
 *
 * <p>Client queue: connection/mirror binding + execution.
 * Server queue: authoritative identity/entity for the concrete MinecraftServer.
 * The two snapshots are separate roles and are never compared as one object.
 */
public interface EnqueueScheduling {

    /** Client-side binding: connection generation and local mirror entity. */
    record ClientBinding(String connectionId, Object clientRef, Object mirrorEntityRef, Object levelRef) {
        public boolean ready() {
            return connectionId != null && clientRef != null && mirrorEntityRef != null && levelRef != null;
        }

        public boolean sameAs(ClientBinding other) {
            return other != null && Objects.equals(connectionId, other.connectionId)
                    && clientRef == other.clientRef
                    && mirrorEntityRef == other.mirrorEntityRef
                    && levelRef == other.levelRef;
        }
    }

    /** Server-side authoritative sample for one concrete MinecraftServer. */
    record ServerSample(String worldId, String serverSession, long epoch, Object serverRef, Object entityRef) {
        public boolean hasIdentity() {
            return worldId != null && !worldId.isBlank()
                    && serverSession != null && !serverSession.isBlank();
        }

        public boolean sameAs(ServerSample other) {
            if (other == null) return false;
            return epoch == other.epoch
                    && Objects.equals(worldId, other.worldId)
                    && Objects.equals(serverSession, other.serverSession)
                    && serverRef == other.serverRef
                    && entityRef == other.entityRef;
        }
    }

    /** Client-thread capture; never reads server-only state. */
    ClientBinding captureClient(UUID companion);

    /** Server-thread capture; never reads client rendering state. */
    CompletableFuture<ServerSample> sampleServerAsync(UUID companion);

    boolean onClientThread();

    void executeOnClient(Runnable task);
}
