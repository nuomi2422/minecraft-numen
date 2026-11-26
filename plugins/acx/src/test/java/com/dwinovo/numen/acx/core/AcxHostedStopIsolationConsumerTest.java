package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxCompletionProbe;
import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxPortSchema;
import com.dwinovo.numen.acx.api.AcxRunBinding;
import com.dwinovo.numen.acx.api.AcxRunRecord;
import com.dwinovo.numen.acx.api.AcxStatus;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import com.dwinovo.numen.acx.api.AcxTool;
import com.dwinovo.numen.acx.api.AcxTool.AcxCallContext;
import com.dwinovo.numen.acx.api.AcxToolPort;
import com.dwinovo.numen.acx.api.AcxToolSchema;
import com.dwinovo.numen.plugins.acx.AcxRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Real lifecycle/receipt consumers; opaque body tokens do not establish body cancellation or Minecraft effects. */
class AcxHostedStopIsolationConsumerTest {
    @Test
    void hostedStopInvalidatesBothBodiesButFreeRunConsumesItsOriginalReceiptOnce() {
        Fixture f = new Fixture();
        f.sessions.invalidateHostedBodies();
        Snapshot a = Snapshot.of(f.original[0]), b = Snapshot.of(f.original[1]);
        boolean freeMatches = f.original[2].matchesBinding(f.bindings[2]);
        Map<String, Object> refusedA = f.resume(0), refusedB = f.resume(1);
        int probesAfterRefusal = f.host.probed.size();
        int pendingAfterStop = f.adapter.pendingCount();
        Map<String, Object> freeResume = f.resume(2);

        assertAll("host stop isolates hosted identities, not the host-free business run",
                () -> a.assertInvalidated(f.records[0]), () -> b.assertInvalidated(f.records[1]),
                () -> assertSame(f.original[0], f.sessions.session(f.original[0].runId())),
                () -> assertSame(f.original[1], f.sessions.session(f.original[1].runId())),
                () -> assertSame(f.records[0], f.original[0].record()),
                () -> assertSame(f.records[1], f.original[1].record()),
                () -> assertEquals(Boolean.FALSE, refusedA.get("success")),
                () -> assertEquals(Boolean.FALSE, refusedB.get("success")),
                () -> assertEquals(0, probesAfterRefusal),
                () -> assertEquals(1, pendingAfterStop, "only the free receipt remains"),
                () -> assertTrue(freeMatches), () -> f.assertFreeDone(freeResume));
    }

    @Test
    void oneCleanupFailureCannotLeaveAnotherHostedIdentityLiveOrBreakFreeConsumption() {
        Fixture f = new Fixture();
        CleanupFault fault = new CleanupFault(f.adapter);
        f.registry.register(fault);
        RuntimeException observed = null;
        try {
            f.sessions.invalidateHostedBodies();
        } catch (RuntimeException failure) {
            observed = failure; // Either propagation policy is allowed; identity safety is not optional.
        }
        Snapshot a = Snapshot.of(f.original[0]), b = Snapshot.of(f.original[1]);
        Snapshot free = Snapshot.of(f.original[2]);
        boolean freeMatches = f.original[2].matchesBinding(f.bindings[2]);
        // Do not resume a possibly still-live hosted identity merely to diagnose the first stop.
        fault.clearFault();
        try {
            f.sessions.invalidateHostedBodies();
        } finally {
            f.sessions.purgeFinished(); // Existing owner retirement retries exact-run receipt cleanup.
        }
        AcxSessionManager.Session retiredA = f.sessions.session(f.original[0].runId());
        AcxSessionManager.Session retiredB = f.sessions.session(f.original[1].runId());
        AcxSessionManager.Session retainedFree = f.sessions.session(f.original[2].runId());
        AcxRunRecord freeBeforeResume = f.original[2].record();
        Map<String, Object> freeResume = f.resume(2);

        assertAll("first stop identity snapshot; permitted cleanup exception: " + observed,
                () -> assertEquals(1, fault.failures),
                () -> a.assertInvalidated(f.records[0]), () -> b.assertInvalidated(f.records[1]),
                () -> assertFalse(free.invalidated()), () -> assertTrue(freeMatches),
                () -> assertSame(f.records[2], free.record()),
                () -> assertSame(f.records[0], f.original[0].record()),
                () -> assertSame(f.records[1], f.original[1].record()),
                () -> assertNull(retiredA), () -> assertNull(retiredB),
                () -> assertSame(f.original[2], retainedFree),
                () -> assertSame(f.records[2], freeBeforeResume), () -> f.assertFreeDone(freeResume));
    }

    @Test
    void cleanupFailureStillClosesAQueuedHostedRunBeforeItsOldRunnableCanDispatch() {
        Fixture f = new Fixture(true);
        CleanupFault fault = new CleanupFault(f.adapter);
        f.registry.register(fault);
        IllegalStateException observed = assertThrows(IllegalStateException.class, f.sessions::invalidateHostedBodies);
        assertEquals("one cleanup failure", observed.getMessage(), "the original cleanup exception remains visible");
        Snapshot a = Snapshot.of(f.original[0]), b = Snapshot.of(f.original[1]);
        boolean queuedCancellation = f.original[1].isCancelRequested();
        fault.clearFault();
        f.queued.removeFirst().run();
        AcxRunRecord neverDispatched = f.original[1].record();
        assertEquals(AcxStatus.PAUSED, neverDispatched.status());
        assertEquals(0, neverDispatched.completedStepIndex());
        assertEquals("cancel_requested_before_start", neverDispatched.pausedReason());
        assertTrue(neverDispatched.vars().isEmpty());
        assertEquals(2, f.host.dispatched.size(), "the retired queued body cannot get its first action");
        assertTrue(f.host.probed.isEmpty());
        assertEquals(2, f.sessions.purgeFinished(), "ordinary retirement retries the first incomplete cleanup");
        Map<String,Object> freeResume = f.resume(2);
        assertAll("all hosted identities close before cleanup, including a not-yet-started attempt",
                () -> a.assertInvalidated(f.records[0]), () -> b.assertInvalidated(null),
                () -> assertTrue(queuedCancellation),
                () -> assertFalse(f.original[2].isBodyInvalidated()),
                () -> assertEquals("RUNNING", data(freeResume).get("state")),
                () -> assertEquals(AcxStatus.SUCCESS, f.original[2].record().status()),
                () -> assertEquals(List.of(new Consumption(f.original[2].runId(), "after", 42)), f.consumer.consumed),
                () -> assertEquals(List.of(f.host.dispatched.get(1)), f.host.probed),
                () -> assertEquals(2, f.host.dispatched.size()),
                () -> assertEquals(0, f.adapter.pendingCount()));
    }

    @Test
    void exactBodyInvalidationKeepsTheOtherBodyAndFreeRunBoundToTheirOriginalTasks() {
        Fixture f = new Fixture();
        f.sessions.invalidateBody(f.bindings[0].bodyToken());
        Snapshot.of(f.original[0]).assertInvalidated(f.records[0]);
        assertSame(f.records[1], f.original[1].record());
        assertTrue(f.original[1].matchesBinding(f.bindings[1]));
        assertFalse(f.original[1].isBodyInvalidated());
        assertTrue(f.original[2].matchesBinding(f.bindings[2]));
        assertEquals(2, f.adapter.pendingCount());
        assertEquals(Boolean.FALSE, f.resume(0).get("success"));
        assertTrue(f.host.probed.isEmpty());
        data(f.resume(1));
        data(f.resume(2));
        assertEquals(AcxStatus.SUCCESS, f.original[1].record().status());
        assertEquals(AcxStatus.SUCCESS, f.original[2].record().status());
        assertEquals(List.of(new Consumption(f.original[1].runId(), "after", 42),
                new Consumption(f.original[2].runId(), "after", 42)), f.consumer.consumed);
        assertEquals(List.of(f.host.dispatched.get(1), f.host.dispatched.get(2)), f.host.probed);
        assertEquals(3, f.host.dispatched.size());
        assertEquals(0, f.adapter.pendingCount());
        assertSame(f.records[0], f.original[0].record());
    }

    private record Snapshot(boolean invalidated, Object body, Object yield, AcxRunRecord record) {
        static Snapshot of(AcxSessionManager.Session s) {
            return new Snapshot(s.isBodyInvalidated(), s.bodyToken(), s.yieldBinding(), s.record());
        }
        void assertInvalidated(AcxRunRecord original) {
            assertAll(() -> assertTrue(invalidated), () -> assertNull(body), () -> assertNull(yield),
                    () -> assertSame(original, record)); // Historical PAUSED is not a new cancellation record.
        }
    }

    private static final class Fixture {
        final AcxRegistry registry = new AcxRegistry();
        final ControlledHost host = new ControlledHost();
        final RecordingConsumer consumer = new RecordingConsumer();
        final PortToolAdapter adapter = PortToolAdapter.builder(host).probe(host).build();
        final AcxRunBinding[] bindings = {new AcxRunBinding("ownerA", new Object(), new Object()),
                new AcxRunBinding("ownerB", new Object(), new Object()), AcxRunBinding.named("free")};
        final AcxSessionManager.Session[] original = new AcxSessionManager.Session[3];
        final AcxRunRecord[] records = new AcxRunRecord[3];
        final AcxSessionManager sessions;
        final AcxFacade facade;
        final java.util.ArrayDeque<Runnable> queued = new java.util.ArrayDeque<>();

        Fixture() { this(false); }

        Fixture(boolean queueSecond) {
            registry.register(adapter);
            registry.register(consumer);
            AcxDefinition definition = AcxLoader.parseJson("""
                    {"name":"hosted_stop","version":"1","steps":[
                      {"id":"act","block":"async_action"},
                      {"id":"after","block":"consume","params":{"val":"$act.value"}}
                    ]}
                    """, registry);
            AcxRunner runner = AcxRunner.builder().tools(registry).build();
            int[] scheduled = {0};
            sessions = new AcxSessionManager(runner, task -> {
                if (queueSecond && scheduled[0]++ == 1) queued.addLast(task);
                else task.run();
            }); // Default retention cannot evict these three.
            facade = new AcxFacade(sessions, name -> "hosted_stop".equals(name) ? definition : null, registry, null);
            for (int i = 0; i < bindings.length; i++) {
                Map<String, Object> accepted = data(facade.executeBound(Map.of("ac_name", "hosted_stop"), bindings[i]));
                assertEquals("RUNNING", accepted.get("status")); // Acceptance is not the completed attempt's status.
                assertEquals(Boolean.TRUE, accepted.get("async"));
                original[i] = sessions.session((String) accepted.get("run_id"));
                records[i] = original[i].record();
                assertTrue(original[i].matchesBinding(bindings[i]));
                assertSame(bindings[i].bodyToken(), original[i].bodyToken());
                assertSame(bindings[i].yieldBinding(), original[i].yieldBinding());
                assertFalse(original[i].isBodyInvalidated());
                if (queueSecond && i == 1) {
                    assertTrue(original[i].isRunning());
                    assertNull(records[i]);
                    assertEquals(1, queued.size());
                    continue;
                }
                assertFalse(original[i].isRunning());
                assertNull(original[i].error());
                assertEquals(AcxStatus.PAUSED, records[i].status());
                assertEquals(0, records[i].completedStepIndex());
                assertEquals("PAUSED", data(facade.statusBound(Map.of("run_id", original[i].runId()), bindings[i])).get("state"));
            }
            assertEquals(queueSecond ? 2 : 3, adapter.pendingCount());
            assertEquals(queueSecond ? 2 : 3, host.dispatched.stream().map(d -> d.identity().runId()).distinct().count());
            assertEquals(queueSecond ? 2 : 3, host.dispatched.stream().map(Dispatch::taskId).distinct().count());
            assertTrue(host.probed.isEmpty());
            assertTrue(consumer.consumed.isEmpty());
        }
        Map<String, Object> resume(int i) {
            return facade.resumeBound(Map.of("run_id", original[i].runId()), bindings[i]);
        }
        void assertFreeDone(Map<String, Object> response) {
            AcxSessionManager.Session free = original[2];
            assertAll("same free run receives the actual DONE value once without redispatch",
                    () -> assertEquals("RUNNING", data(response).get("state")),
                    () -> assertEquals(Boolean.TRUE, data(response).get("async")),
                    () -> assertEquals(free.runId(), data(response).get("run_id")),
                    () -> assertEquals(0, data(response).get("resumed_from_step")),
                    () -> assertSame(free, sessions.session(free.runId())),
                    () -> assertFalse(free.isBodyInvalidated()), () -> assertTrue(free.matchesBinding(bindings[2])),
                    () -> assertNull(free.error()), () -> assertEquals(AcxStatus.SUCCESS, free.record().status()),
                    () -> assertEquals(2, free.record().completedStepIndex()),
                    () -> assertEquals("SUCCESS", data(facade.statusBound(Map.of("run_id", free.runId()), bindings[2])).get("state")),
                    () -> assertEquals(List.of(new Consumption(free.runId(), "after", 42)), consumer.consumed),
                    () -> assertEquals(List.of(host.dispatched.get(2)), host.probed),
                    () -> assertEquals(3, host.dispatched.size()), () -> assertEquals(0, adapter.pendingCount()));
        }
    }

    private record Identity(String runId, String fingerprint, String path, String stepId) {
        static Identity of(AcxCallContext ctx) {
            return new Identity(ctx.runId(), ctx.definitionFingerprint(), ctx.invocationPath(), ctx.stepId());
        }
    }
    private record Dispatch(String taskId, Identity identity) { }
    private record Consumption(String runId, String stepId, Object value) { }

    private static final class RecordingConsumer implements AcxTool {
        final List<Consumption> consumed = new ArrayList<>();
        @Override public String name() { return "consume"; }
        @Override public AcxToolSchema schema() {
            return AcxToolSchema.builder().param("val", AcxToolSchema.ParamType.INTEGER).required("val").build();
        }
        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            consumed.add(new Consumption(ctx.runId(), ctx.stepId(), params.get("val")));
            return AcxStepOutcome.success(Map.of("value", params.get("val")));
        }
    }

    private static final class ControlledHost implements AcxToolPort, AcxCompletionProbe {
        final List<Dispatch> dispatched = new ArrayList<>(), probed = new ArrayList<>();
        @Override public String name() { return "async_action"; }
        @Override public AcxPortSchema schema() { return new AcxPortSchema(Map.of(), Set.of("value"), false); }
        @Override public Result invoke(Map<String, Object> params) { throw new AssertionError("context required"); }
        @Override public Result invoke(Map<String, Object> params, AcxCallContext ctx) {
            assertTrue(params.isEmpty());
            assertFalse(ctx.isResuming(), "an accepted action must not be dispatched again");
            String task = "task-" + (dispatched.size() + 1);
            dispatched.add(new Dispatch(task, Identity.of(ctx)));
            return Result.accepted(task, Map.of(), "accepted, not completed");
        }
        @Override public Snapshot probe(String taskId) { throw new AssertionError("context required"); }
        @Override public Snapshot probe(String taskId, AcxCallContext ctx) {
            Dispatch actual = new Dispatch(taskId, Identity.of(ctx));
            assertTrue(ctx.isResuming());
            assertTrue(dispatched.contains(actual), "probe must retain exact task/run/call-site identity");
            probed.add(actual);
            return Snapshot.done(Map.of("value", 42), "original task is DONE");
        }
    }

    private static final class CleanupFault implements AcxTool {
        final PortToolAdapter adapter;
        boolean failOnce = true;
        int failures;
        CleanupFault(PortToolAdapter adapter) { this.adapter = adapter; }
        void clearFault() { failOnce = false; }
        @Override public String name() { return "cleanup_fault"; }
        @Override public AcxToolSchema schema() { return AcxToolSchema.builder().build(); }
        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            throw new AssertionError("cleanup-only tool is not in the script");
        }
        @Override public void discardRunReceipts(String runId) {
            if (!failOnce) return;
            failOnce = false;
            failures++;
            adapter.discardRunReceipts(runId); // No reliance on registry names ordering; never wrap the adapter.
            throw new IllegalStateException("one cleanup failure");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        return (Map<String, Object>) response.get("data");
    }
}
