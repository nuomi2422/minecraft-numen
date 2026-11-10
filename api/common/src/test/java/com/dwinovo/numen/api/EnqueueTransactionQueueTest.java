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
        boolean inClientTask;
        boolean inServerTask;
        int rejectClientScheduleAt = -1;
        int clientScheduleCalls;

        @Override
        public ClientBinding captureClient(UUID companion) {
            if (!inClientTask) throw new IllegalStateException("captureClient must run on the client queue");
            return currentClient;
        }

        @Override
        public CompletableFuture<ServerSample> sampleServerAsync(UUID companion) {
            CompletableFuture<ServerSample> local = new CompletableFuture<>();
            serverQueue.add(() -> {
                if (!inServerTask) throw new IllegalStateException("server capture must run on the server queue");
                if (failServerSample) local.completeExceptionally(new IllegalStateException("sample boom"));
                else local.complete(currentServer);
            });
            return local;
        }

        @Override
        public boolean onClientThread() {
            return clientThread;
        }

        @Override
        public void executeOnClient(Runnable task) {
            clientScheduleCalls++;
            if (clientScheduleCalls == rejectClientScheduleAt) throw new IllegalStateException("client queue rejected");
            clientQueue.add(task);
        }

        void runClient() {
            List<Runnable> batch = new ArrayList<>(clientQueue);
            clientQueue.clear();
            inClientTask = true;
            try {
                for (Runnable r : batch) r.run();
            } finally {
                inClientTask = false;
            }
        }

        void runServer() {
            List<Runnable> batch = new ArrayList<>(serverQueue);
            serverQueue.clear();
            inServerTask = true;
            try {
                for (Runnable r : batch) r.run();
            } finally {
                inServerTask = false;
            }
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
        int[] result = {0};
        scheduling.executeOnClient(() -> {
            EnqueueScheduling.ClientBinding binding = scheduling.captureClient(UUID.randomUUID());
            EnqueueScheduling.ServerSample serverOnClient = new EnqueueScheduling.ServerSample(null, null, 0L, null, null);
            if (binding.ready() && serverOnClient.hasIdentity()) dispatch.dispatch(UUID.randomUUID(), "/stop");
            result[0] = dispatch.calls.get();
        });
        scheduling.runClient();
        return result[0];
    }

    @Test
    void modeledOldFlowRejectsNormalRequest() {
        TwoQueue scheduling = new TwoQueue();
        CountingDispatch dispatch = new CountingDispatch();
        assertEquals(0, modeledOldFlow(scheduling, dispatch));
    }


    @Test
    void oldActuatorConsumerStillExecutesOnceWithoutServerIdentity() {
        TwoQueue scheduling = new TwoQueue();
        scheduling.clientThread = false;
        CountingDispatch dispatch = new CountingDispatch();
        UUID companion = UUID.randomUUID();
        var future = NumenActuator.enqueueLegacyForTest(companion, "hello", scheduling, dispatch, true);
        assertEquals(0, dispatch.calls.get(), "queued until client runs");
        scheduling.runClient();
        scheduling.runClient(); // legacy off-thread path schedules its own client task
        assertEquals(true, future.join());
        assertEquals(1, dispatch.calls.get(), "legacy consumer executes exactly once");
    }

    @Test
    void clientScheduleRejectionCompletesFutureAndDispatchesNothing() {
        TwoQueue scheduling = new TwoQueue();
        scheduling.rejectClientScheduleAt = 2; // first = initial task, second = post-sample continuation
        CountingDispatch dispatch = new CountingDispatch();
        var future = NumenGateway.enqueueReceiptAsync(UUID.randomUUID(), "/stop", scheduling, dispatch, true);
        scheduling.runClient();
        scheduling.runServer();
        assertThrows(java.util.concurrent.CompletionException.class, future::join);
        assertEquals(0, dispatch.calls.get());
    }

    @Test
    void dualTargetOutOfOrderDoesNotCross() {
        TwoQueue scheduling = new TwoQueue();
        List<UUID> dispatched = new ArrayList<>();
        var dispatch = new NumenGateway.EnqueueDispatch() {
            @Override
            public NumenGateway.Executed dispatch(UUID companion, String message) {
                dispatched.add(companion);
                return new NumenGateway.Executed(Delivery.SEEN, "cmd-" + companion);
            }
        };
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var fa = NumenGateway.enqueueReceiptAsync(a, "/stop", scheduling, dispatch, true);
        var fb = NumenGateway.enqueueReceiptAsync(b, "/stop", scheduling, dispatch, true);
        scheduling.runClient();
        scheduling.runServer();
        scheduling.runClient();
        scheduling.runServer();
        assertEquals(NumenGateway.EnqueueReceipt.IdentityStatus.OK, fa.join().identityStatus());
        assertEquals(NumenGateway.EnqueueReceipt.IdentityStatus.OK, fb.join().identityStatus());
        assertEquals(2, dispatched.size());
        assertEquals(1, dispatched.stream().filter(x -> x.equals(a)).count());
        assertEquals(1, dispatched.stream().filter(x -> x.equals(b)).count());
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
