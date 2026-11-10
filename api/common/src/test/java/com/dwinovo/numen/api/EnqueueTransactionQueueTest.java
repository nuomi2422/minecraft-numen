package com.dwinovo.numen.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * R-08 two-queue fixtures. Client and server queues are independent and driven explicitly;
 * no "add then immediately run", no flush-rerun. The old flow is modeled as a runnable
 * counterexample (client cannot sample server-only state), labeled as modeled, not production replay.
 */
class EnqueueTransactionQueueTest {

    static final class TwoQueue implements EnqueueScheduling {
        final List<Runnable> clientQueue = new ArrayList<>();
        final List<Runnable> serverQueue = new ArrayList<>();
        ClientBinding currentClient = new ClientBinding("conn-1", new Object(), new Object(), new Object());
        ServerSample currentServer = new ServerSample("w", "s", 7, new Object(), new Object());
        boolean clientThread = true;
        boolean failServerSample;
        CompletableFuture<ServerSample> lastServerFuture;

        @Override
        public ClientBinding captureClient(UUID companion) {
            return currentClient;
        }

        @Override
        public CompletableFuture<ServerSample> sampleServerAsync(UUID companion) {
            lastServerFuture = new CompletableFuture<>();
            serverQueue.add(() -> {
                if (failServerSample) lastServerFuture.completeExceptionally(new IllegalStateException("sample boom"));
                else lastServerFuture.complete(currentServer);
            });
            return lastServerFuture;
        }

        @Override
        public boolean onClientThread() {
            return clientThread;
        }

        @Override
        public void executeOnClient(Runnable task) {
            clientQueue.add(task);
        }

        void runClient() {
            List<Runnable> batch = new ArrayList<>(clientQueue);
            clientQueue.clear();
            for (Runnable r : batch) r.run();
        }

        void runServer() {
            List<Runnable> batch = new ArrayList<>(serverQueue);
            serverQueue.clear();
            for (Runnable r : batch) r.run();
        }
    }

    static final class CountingDispatch implements NumenGateway.EnqueueDispatch {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public NumenGateway.Executed dispatch(UUID companion, String message) {
            calls.incrementAndGet();
            return new NumenGateway.Executed(Delivery.SEEN, "cmd-1");
        }
    }

    /** Modeled old flow: client-side sample missing -> precheck rejects -> 0 dispatch. */
    private static int modeledOldFlow(TwoQueue scheduling, CountingDispatch dispatch) {
        EnqueueScheduling.ClientBinding binding = scheduling.captureClient(UUID.randomUUID());
        EnqueueScheduling.ServerSample serverOnClient = new EnqueueScheduling.ServerSample(null, null, 0L, null, null);
        if (!binding.ready() || !serverOnClient.hasIdentity()) return 0;
        dispatch.dispatch(UUID.randomUUID(), "/stop");
        return dispatch.calls.get();
    }

    @Test
    void modeledOldFlowRejectsNormalRequest() {
        TwoQueue scheduling = new TwoQueue();
        CountingDispatch dispatch = new CountingDispatch();
        assertEquals(0, modeledOldFlow(scheduling, dispatch));
    }

    @Test
    void normalAsyncRunsExactlyOnce() {
        TwoQueue scheduling = new TwoQueue();
        CountingDispatch dispatch = new CountingDispatch();
        UUID companion = UUID.randomUUID();

        CompletableFuture<NumenGateway.EnqueueReceipt> future =
                NumenGateway.enqueueReceiptAsync(companion, "/stop", scheduling, dispatch, true);
        scheduling.runClient();
        scheduling.runServer();
        scheduling.runClient();
        scheduling.runServer();

        NumenGateway.EnqueueReceipt receipt = future.join();
        assertEquals(1, dispatch.calls.get());
        assertEquals(NumenGateway.EnqueueReceipt.IdentityStatus.OK, receipt.identityStatus());
    }

    @Test
    void replacedBindingAfterServerSampleNeverDispatches() {
        TwoQueue scheduling = new TwoQueue();
        CountingDispatch dispatch = new CountingDispatch();
        UUID companion = UUID.randomUUID();

        CompletableFuture<NumenGateway.EnqueueReceipt> future =
                NumenGateway.enqueueReceiptAsync(companion, "/stop", scheduling, dispatch, true);
        scheduling.runClient();
        scheduling.currentClient = new EnqueueScheduling.ClientBinding("conn-2", new Object(), new Object(), new Object());
        scheduling.runServer();
        scheduling.runClient();

        NumenGateway.EnqueueReceipt receipt = future.join();
        assertEquals(0, dispatch.calls.get());
        assertEquals(Delivery.REJECTED, receipt.delivery());
        assertEquals(NumenGateway.EnqueueReceipt.IdentityStatus.CONNECTION_CHANGED, receipt.identityStatus());
    }

    @Test
    void sampleFailureCompletesInsteadOfHanging() {
        TwoQueue scheduling = new TwoQueue();
        scheduling.failServerSample = true;
        CountingDispatch dispatch = new CountingDispatch();
        CompletableFuture<NumenGateway.EnqueueReceipt> future =
                NumenGateway.enqueueReceiptAsync(UUID.randomUUID(), "/stop", scheduling, dispatch, true);
        scheduling.runClient();
        scheduling.runServer();
        NumenGateway.EnqueueReceipt receipt = future.join();
        assertEquals(0, dispatch.calls.get());
        assertEquals(NumenGateway.EnqueueReceipt.IdentityStatus.NO_IDENTITY, receipt.identityStatus());
    }

    @Test
    void lateServerCompletionStillDispatchesOnce() {
        TwoQueue scheduling = new TwoQueue();
        CountingDispatch dispatch = new CountingDispatch();
        CompletableFuture<NumenGateway.EnqueueReceipt> future =
                NumenGateway.enqueueReceiptAsync(UUID.randomUUID(), "/stop", scheduling, dispatch, true);
        scheduling.runClient();
        assertEquals(0, dispatch.calls.get(), "nothing executes before the server sample resolves");
        scheduling.runServer();
        scheduling.runClient();
        assertEquals(1, dispatch.calls.get());
        scheduling.runServer();
        future.join();
        assertEquals(1, dispatch.calls.get(), "no retry after completion");
    }

    @Test
    void legacyEntryPointKeepsOriginalExecutionSemantics() {
        TwoQueue scheduling = new TwoQueue();
        CountingDispatch dispatch = new CountingDispatch();
        UUID companion = UUID.randomUUID();
        assertEquals(Delivery.SEEN, NumenGateway.enqueueLegacy(companion, "hello", scheduling, dispatch, true));
        assertEquals(1, dispatch.calls.get());

        TwoQueue offThread = new TwoQueue();
        offThread.clientThread = false;
        CountingDispatch dispatch2 = new CountingDispatch();
        assertEquals(Delivery.HANDED_OFF, NumenGateway.enqueueLegacy(companion, "hello", offThread, dispatch2, true));
        assertEquals(0, dispatch2.calls.get());
        offThread.runClient();
        assertEquals(1, dispatch2.calls.get(), "legacy off-thread path still dispatches once");
    }

    @Test
    void dispatchExceptionRestoresIdentityContext() {
        TwoQueue scheduling = new TwoQueue();
        var dispatch = new NumenGateway.EnqueueDispatch() {
            @Override
            public NumenGateway.Executed dispatch(UUID companion, String message) {
                throw new IllegalStateException("boom");
            }
        };
        UUID companion = UUID.randomUUID();
        CompletableFuture<NumenGateway.EnqueueReceipt> future =
                NumenGateway.enqueueReceiptAsync(companion, "/stop", scheduling, dispatch, true);
        scheduling.runClient();
        scheduling.runServer();
        scheduling.runClient();
        assertThrows(Exception.class, future::join);
        assertNull(NumenGateway.currentIdentityToken(), "identity context must not leak");
    }
}
