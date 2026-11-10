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
 * R-07: real transaction queue fixtures over the same entry the production path uses.
 * The fake scheduler controls when the server sample resolves and what the client sees
 * before dispatch, so "replaced after sample, before client executes" is exercised for real.
 */
class EnqueueTransactionQueueTest {

    private static EnqueueScheduling.Sample sample(Object entity) {
        return new EnqueueScheduling.Sample("w", "s", 7, "c1", entity, entity, entity);
    }

    static final class FakeScheduling implements EnqueueScheduling {
        EnqueueScheduling.Sample current = sample(new Object());
        CompletableFuture<EnqueueScheduling.Sample> pending;
        boolean deferSample;
        final List<Runnable> clientTasks = new ArrayList<>();

        @Override
        public EnqueueScheduling.Sample sampleNow(UUID companion) {
            return current;
        }

        @Override
        public CompletableFuture<EnqueueScheduling.Sample> sampleAsync(UUID companion) {
            if (deferSample) {
                pending = new CompletableFuture<>();
                return pending;
            }
            return CompletableFuture.completedFuture(current);
        }

        @Override
        public boolean onClientThread() {
            return true;
        }

        @Override
        public void executeOnClient(Runnable task) {
            clientTasks.add(task);
            task.run();
        }

        @Override
        public UUID liveTargetUuid(UUID companion) {
            return current.entityRef() == null ? null : companion;
        }

        void flush() {
            List<Runnable> copy = new ArrayList<>(clientTasks);
            clientTasks.clear();
            for (Runnable r : copy) r.run();
        }
    }

    static final class CountingDispatch implements NumenGateway.EnqueueDispatch {
        final AtomicInteger calls = new AtomicInteger();
        final boolean throwsInstead;

        CountingDispatch(boolean throwsInstead) {
            this.throwsInstead = throwsInstead;
        }

        @Override
        public NumenGateway.Executed dispatch(UUID companion, String message) {
            calls.incrementAndGet();
            if (throwsInstead) throw new IllegalStateException("dispatch boom");
            return new NumenGateway.Executed(Delivery.SEEN, "cmd-1");
        }
    }

    @Test
    void normalTransactionExecutesExactlyOnce() {
        var scheduling = new FakeScheduling();
        var dispatch = new CountingDispatch(false);
        var receipt = NumenGateway.enqueueReceipt(UUID.randomUUID(), "/stop", scheduling, dispatch, true);
        assertEquals(1, dispatch.calls.get());
        assertEquals(NumenGateway.EnqueueReceipt.IdentityStatus.OK, receipt.identityStatus());
        assertEquals(Delivery.SEEN, receipt.delivery());
    }

    @Test
    void asyncReplacedAfterServerSampleNeverDispatches() {
        var scheduling = new FakeScheduling();
        scheduling.deferSample = true;
        var dispatch = new CountingDispatch(false);
        UUID companion = UUID.randomUUID();

        var future = NumenGateway.enqueueReceiptAsync(companion, "/stop", scheduling, dispatch, true);
        assertNotNull(scheduling.pending, "server sample must be pending");
        // server sampled entity A; before the client continuation runs, the target is replaced.
        var sampled = scheduling.current;
        scheduling.current = sample(new Object());
        scheduling.pending.complete(sampled);

        var receipt = future.join();
        assertEquals(0, dispatch.calls.get(), "replaced target must not dispatch");
        assertEquals(Delivery.REJECTED, receipt.delivery(), "unexecuted rejection must not look accepted");
        assertEquals(NumenGateway.EnqueueReceipt.IdentityStatus.CONNECTION_CHANGED, receipt.identityStatus());
    }

    @Test
    void asyncNormalRunsOnceAndCompletes() {
        var scheduling = new FakeScheduling();
        var dispatch = new CountingDispatch(false);
        UUID companion = UUID.randomUUID();
        var receipt = NumenGateway.enqueueReceiptAsync(companion, "/stop", scheduling, dispatch, true).join();
        assertEquals(1, dispatch.calls.get());
        assertEquals(NumenGateway.EnqueueReceipt.IdentityStatus.OK, receipt.identityStatus());
    }

    @Test
    void dispatchExceptionRestoresIdentityContext() {
        var scheduling = new FakeScheduling();
        var dispatch = new CountingDispatch(true);
        UUID companion = UUID.randomUUID();
        assertThrows(IllegalStateException.class,
                () -> NumenGateway.enqueueReceipt(companion, "/stop", scheduling, dispatch, true));
        assertNull(NumenGateway.currentIdentityToken(), "identity context must not leak");
    }

    @Test
    void lateCompletionAfterTimeoutStillDispatchesOnce() {
        var scheduling = new FakeScheduling();
        scheduling.deferSample = true;
        var dispatch = new CountingDispatch(false);
        UUID companion = UUID.randomUUID();
        var future = NumenGateway.enqueueReceiptAsync(companion, "/stop", scheduling, dispatch, true);
        // caller "times out" before the server sample arrives; late completion must not re-run.
        assertEquals(0, dispatch.calls.get());
        scheduling.pending.complete(scheduling.current);
        future.join();
        assertEquals(1, dispatch.calls.get());
        assertEquals(1, dispatch.calls.get(), "no retry on late completion");
    }
}
