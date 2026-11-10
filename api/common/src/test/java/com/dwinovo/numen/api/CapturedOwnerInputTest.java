package com.dwinovo.numen.api;

import com.dwinovo.numen.client.agent.OwnerInputAdmission;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** P1-F: actual captured Gateway dispatch with explicit delayed client queue. Source only, not yet run. */
class CapturedOwnerInputTest {
    @Test void oldVoiceAfterStopCannotDispatchOrRunResumeCommand() {
        UUID id = UUID.randomUUID();
        var input = new OwnerInputAdmission(id);
        var old = input.capture();
        input.ownerStopped(); // same operation called unconditionally by ownerStop (idle included)
        var scheduling = new EnqueueTransactionQueueTest.TwoQueue();
        var dispatch = new EnqueueTransactionQueueTest.CountingDispatch();
        assertEquals(Delivery.REJECTED, NumenGateway.enqueueCaptured(id, "/stop resume", old,
                scheduling, dispatch, () -> input));
        assertEquals(0, dispatch.calls.get());
    }
    @Test void resumeDoesNotReviveOldInputButNewInputWorks() {
        UUID id = UUID.randomUUID();
        var input = new OwnerInputAdmission(id);
        var old = input.capture();
        input.ownerStopped();
        // Resume changes only the existing pause latch, never admission epoch.
        com.dwinovo.numen.client.agent.OwnerStopTestBridge.assertOwnerResumeClearsLatch();
        var scheduling = new EnqueueTransactionQueueTest.TwoQueue();
        var dispatch = new EnqueueTransactionQueueTest.CountingDispatch();
        assertEquals(Delivery.REJECTED, NumenGateway.enqueueCaptured(id, "old", old, scheduling, dispatch, () -> input));
        assertEquals(Delivery.SEEN, NumenGateway.enqueueCaptured(id, "new", input.capture(), scheduling, dispatch, () -> input));
        assertEquals(1, dispatch.calls.get());
    }
    @Test void stopBetweenEnqueueAndClientConsumptionRejectsOldTicket() {
        UUID id = UUID.randomUUID();
        var input = new OwnerInputAdmission(id);
        var scheduling = new EnqueueTransactionQueueTest.TwoQueue();
        scheduling.clientThread = false;
        var dispatch = new EnqueueTransactionQueueTest.CountingDispatch();
        assertEquals(Delivery.HANDED_OFF, NumenGateway.enqueueCaptured(id, "late", input.capture(),
                scheduling, dispatch, () -> input));
        assertEquals(0, dispatch.calls.get());
        input.ownerStopped();
        scheduling.runClient();
        assertEquals(0, dispatch.calls.get());
    }
    @Test void stoppingADoesNotInvalidateBAndUuidCannotBeSwapped() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var first = new OwnerInputAdmission(a);
        var second = new OwnerInputAdmission(b);
        var ticket = second.capture();
        first.ownerStopped();
        var scheduling = new EnqueueTransactionQueueTest.TwoQueue();
        var dispatch = new EnqueueTransactionQueueTest.CountingDispatch();
        assertEquals(Delivery.SEEN, NumenGateway.enqueueCaptured(b, "new", ticket, scheduling, dispatch, () -> second));
        assertEquals(Delivery.REJECTED, NumenGateway.enqueueCaptured(a, "wrong", ticket, scheduling, dispatch, () -> second));
        assertEquals(1, dispatch.calls.get());
    }
    @Test void genuinelyNewPostStopInputWorksWithoutExplicitResume() {
        UUID id = UUID.randomUUID();
        var input = new OwnerInputAdmission(id);
        input.ownerStopped();
        var scheduling = new EnqueueTransactionQueueTest.TwoQueue();
        var dispatch = new EnqueueTransactionQueueTest.CountingDispatch();
        assertEquals(Delivery.SEEN, NumenGateway.enqueueCaptured(id, "new owner input", input.capture(),
                scheduling, dispatch, () -> input));
        assertEquals(1, dispatch.calls.get());
    }

    @Test void replacementLoopWithSameUuidRejectsQueuedOldTicket() {
        UUID id = UUID.randomUUID();
        var original = new OwnerInputAdmission(id);
        var current = new AtomicReference<>(original);
        var scheduling = new EnqueueTransactionQueueTest.TwoQueue();
        scheduling.clientThread = false;
        var dispatch = new EnqueueTransactionQueueTest.CountingDispatch();
        NumenGateway.enqueueCaptured(id, "late", original.capture(), scheduling, dispatch, current::get);
        current.set(new OwnerInputAdmission(id));
        scheduling.runClient();
        assertEquals(0, dispatch.calls.get());
    }
}
