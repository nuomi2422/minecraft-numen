package com.dwinovo.numen.acx.core;
import com.dwinovo.numen.acx.api.*;
import com.dwinovo.numen.acx.api.AcxTool.AcxCallContext;
import com.dwinovo.numen.plugins.acx.AcxRegistry;
import org.junit.jupiter.api.Test;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import static org.junit.jupiter.api.Assertions.*;
/** Real queued resumes and receipt isolation; no threads, body-stop or game effects certified. */
class AcxQueuedResumeIsolationConsumerTest {
    private static final String NAME = "queued_resume_isolation";
    private static final String SCRIPT = """
            {"name":"queued_resume_isolation","version":"1","steps":[
              {"id":"producer","block":"produce"},
              {"id":"act","block":"async_action"},
              {"id":"consumer","block":"consume","params":{"value":"$producer.value"}}
            ]}
            """;
    @Test
    void queuedResumesConsumeEachRunsCommittedProducerWithoutRedispatch() {
        Fixture f = new Fixture();
        var a = f.startPaused();
        var b = f.startPaused();
        AcxRunRecord priorB = b.record();
        assertNotEquals(a.runId(), b.runId());
        assertEquals(2, f.adapter.pendingCount());
        f.resume(a); f.executor.runNext();
        f.state(a, AcxStatus.SUCCESS, 3);
        f.calls(a, 1, 1);
        assertSame(priorB, b.record());
        f.state(b, AcxStatus.PAUSED, 1);
        f.root42(b.record());
        f.calls(b, 0, 0);
        assertEquals(1, f.adapter.pendingCount());
        f.resume(b); f.executor.runNext();
        f.finished(a, b);
    }
    @Test
    void cancellingQueuedResumePreservesOnlyAsCheckpointAndReceiptUntilExplicitResume() {
        Fixture f = new Fixture();
        var a = f.startPaused();
        var b = f.startPaused();
        AcxRunRecord priorA = a.record(), priorB = b.record();
        f.resume(a); // The real Runnable is queued, not yet inside the runner or host.
        Map<String, Object> cancelled = data(f.facade.cancelBound(Map.of("run_id", a.runId()), f.binding));
        assertEquals(a.runId(), cancelled.get("run_id"));
        assertEquals(Boolean.TRUE, cancelled.get("cancel_requested"));
        assertTrue(a.isRunning());
        assertTrue(a.isCancelRequested(), "request is visible while the queued attempt is in flight");
        assertFalse(b.isCancelRequested());
        assertSame(priorA, a.record());
        assertEquals(2, f.adapter.pendingCount());
        f.executor.runNext(); // Exercise schedule's cancel_requested_before_start branch.
        AcxRunRecord pausedA = f.state(a, AcxStatus.PAUSED, 1);
        assertEquals("cancel_requested_before_start", pausedA.pausedReason());
        assertEquals(priorA.vars(), pausedA.vars());
        assertEquals(priorA.input(), pausedA.input());
        f.root42(pausedA);
        f.calls(a, 0, 0);
        assertEquals(2, f.adapter.pendingCount(), "cancellation did not consume either accepted receipt");
        assertSame(priorB, b.record());
        f.state(b, AcxStatus.PAUSED, 1);
        f.resume(b); f.executor.runNext();
        f.state(b, AcxStatus.SUCCESS, 3);
        f.calls(b, 1, 1);
        assertSame(pausedA, a.record(), "B must not finish, probe or replace A's cancelled pause");
        f.state(a, AcxStatus.PAUSED, 1);
        f.root42(a.record());
        f.calls(a, 0, 0);
        assertEquals(1, f.adapter.pendingCount());
        f.resume(a); f.executor.runNext();
        f.finished(a, b);
    }
    private static final class Fixture {
        final AcxRegistry registry = new AcxRegistry();
        final QueueExecutor executor = new QueueExecutor();
        final ControlledHost host = new ControlledHost();
        final Producer producer = new Producer();
        final Consumer consumer = new Consumer();
        final PortToolAdapter adapter = PortToolAdapter.builder(host).probe(host).build();
        final AcxRunBinding binding = new AcxRunBinding("queued-resume-consumer", new Object());
        final AcxDefinition definition;
        final AcxRunner runner;
        final AcxSessionManager sessions;
        final AcxFacade facade;
        Fixture() {
            registry.register(producer); registry.register(adapter); registry.register(consumer);
            definition = AcxLoader.parseJson(SCRIPT, registry);
            runner = AcxRunner.builder().tools(registry).build();
            sessions = new AcxSessionManager(runner, executor);
            facade = new AcxFacade(sessions, name -> NAME.equals(name) ? definition : null, registry, null);
        }
        AcxSessionManager.Session startPaused() {
            Map<String, Object> accepted = data(facade.executeBound(Map.of("ac_name", NAME), binding));
            assertEquals("RUNNING", accepted.get("status"), "accepted is not completed");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            var s = sessions.session(assertInstanceOf(String.class, accepted.get("run_id")));
            assertNotNull(s);
            assertTrue(s.isRunning());
            assertEquals(1, executor.queue.size());
            executor.runNext();
            AcxRunRecord r = state(s, AcxStatus.PAUSED, 1);
            assertEquals("async_accepted", r.pausedReason());
            root42(r);
            calls(s, 0, 0);
            return s;
        }
        void resume(AcxSessionManager.Session s) {
            AcxRunRecord prior = s.record();
            Map<String, Object> accepted = data(facade.resumeBound(Map.of("run_id", s.runId()), binding));
            assertEquals(s.runId(), accepted.get("run_id"));
            assertEquals("RUNNING", accepted.get("state"), "resume acceptance is not SUCCESS");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            assertEquals(1, accepted.get("resumed_from_step"));
            assertTrue(s.isRunning());
            assertSame(prior, s.record(), "enqueue must not replace the last real record");
            assertEquals(1, executor.queue.size());
        }
        AcxRunRecord state(AcxSessionManager.Session s, AcxStatus expected, int checkpoint) {
            assertSame(s, sessions.session(s.runId()));
            assertSame(definition, s.definition());
            assertTrue(s.matchesBinding(binding));
            assertSame(binding.bodyToken(), s.bodyToken());
            assertFalse(s.isRunning());
            assertFalse(runner.isRunning(s.runId()));
            assertFalse(s.isCancelRequested(), "finishing an attempt clears its cancellation request");
            assertNull(s.error());
            AcxRunRecord r = s.record();
            assertEquals(s.runId(), r.runId());
            assertEquals(NAME, r.acName()); assertEquals("1", r.acVersion());
            assertEquals(AcxFingerprint.of(definition), r.fingerprint());
            assertEquals(expected, r.status(), r.errorMessage());
            assertEquals(checkpoint, r.completedStepIndex());
            Map<String, Object> query = new LinkedHashMap<>(r.toMap());
            query.put("state", expected.name());
            assertEquals(query, data(facade.statusBound(Map.of("run_id", s.runId()), binding)));
            return r;
        }
        void root42(AcxRunRecord r) {
            Map<?, ?> root = assertInstanceOf(Map.class, r.vars().get("_cursor_"));
            assertEquals(1, root.get("step"));
            Map<?, ?> outputs = assertInstanceOf(Map.class, root.get("outputs"));
            assertEquals(Map.of("value", 42), outputs.get("producer"));
        }
        void calls(AcxSessionManager.Session s, int probes, int consumptions) {
            String run = s.runId();
            assertEquals(List.of(run), producer.runs.stream().filter(run::equals).toList());
            assertEquals(1, host.dispatched.stream().filter(r -> r.identity().runId().equals(run)).count());
            assertEquals(probes, host.probed.stream().filter(r -> r.identity().runId().equals(run)).count());
            List<Consumption> actual = consumer.calls.stream().filter(c -> c.runId().equals(run)).toList();
            assertEquals(consumptions == 0 ? List.of() : List.of(new Consumption(run, "consumer", 42)), actual);
            if (consumptions != 0) assertEquals(Integer.class, actual.get(0).value().getClass());
        }
        void finished(AcxSessionManager.Session a, AcxSessionManager.Session b) {
            state(a, AcxStatus.SUCCESS, 3); state(b, AcxStatus.SUCCESS, 3);
            calls(a, 1, 1); calls(b, 1, 1);
            assertEquals(0, adapter.pendingCount());
            assertTrue(executor.queue.isEmpty());
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        return (Map<String, Object>) assertInstanceOf(Map.class, response.get("data"));
    }
    private static final class QueueExecutor implements Executor {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        @Override public void execute(Runnable command) { queue.addLast(command); }
        void runNext() { queue.removeFirst().run(); }
    }
    private static final class Producer implements AcxTool {
        final List<String> runs = new ArrayList<>();
        @Override public String name() { return "produce"; }
        @Override public AcxToolSchema schema() { return new AcxToolSchema(Map.of("value", AcxToolSchema.ParamType.INTEGER), Set.of()); }
        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            runs.add(ctx.runId());
            return AcxStepOutcome.success(Map.of("value", 42));
        }
    }
    private record Consumption(String runId, String stepId, Object value) { }
    private static final class Consumer implements AcxTool {
        final List<Consumption> calls = new ArrayList<>();
        @Override public String name() { return "consume"; }
        @Override public AcxToolSchema schema() { return new AcxToolSchema(Map.of("value", AcxToolSchema.ParamType.ANY), Set.of("value")); }
        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            calls.add(new Consumption(ctx.runId(), ctx.stepId(), params.get("value")));
            return AcxStepOutcome.success(Map.of());
        }
    }
    private record Identity(String runId, String acName, String fingerprint, String path, String stepId, int iteration) {
        static Identity of(AcxCallContext ctx) { return new Identity(ctx.runId(), ctx.acName(), ctx.definitionFingerprint(), ctx.invocationPath(), ctx.stepId(), ctx.loopIter()); }
    }
    private record Receipt(String taskId, Identity identity) { }
    private static final class ControlledHost implements AcxToolPort, AcxCompletionProbe {
        final List<Receipt> dispatched = new ArrayList<>(), probed = new ArrayList<>();
        @Override public String name() { return "async_action"; }
        @Override public AcxPortSchema schema() { return new AcxPortSchema(Map.of(), Set.of("value"), false); }
        @Override public Result invoke(Map<String, Object> params) { throw new AssertionError("context required"); }
        @Override public Result invoke(Map<String, Object> params, AcxCallContext ctx) {
            assertTrue(params.isEmpty());
            assertFalse(ctx.isResuming());
            String task = "task-" + ctx.runId();
            dispatched.add(new Receipt(task, Identity.of(ctx)));
            return Result.accepted(task, Map.of(), "accepted, not done");
        }
        @Override public Snapshot probe(String taskId) { throw new AssertionError("context required"); }
        @Override public Snapshot probe(String taskId, AcxCallContext ctx) {
            assertTrue(ctx.isResuming());
            assertFalse(ctx.isCancelRequested());
            assertEquals("task-" + ctx.runId(), taskId);
            Receipt receipt = new Receipt(taskId, Identity.of(ctx));
            assertTrue(dispatched.contains(receipt), "probe must retain original task/run/definition/path identity");
            probed.add(receipt);
            return Snapshot.done(Map.of("value", -1), "DONE is not the producer's saved 42");
        }
    }
}
