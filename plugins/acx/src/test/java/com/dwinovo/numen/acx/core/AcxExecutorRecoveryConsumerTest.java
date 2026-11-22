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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.jupiter.api.Assertions.*;
/** Real consumer recovery after an executor rejection; only scheduling and host evidence are controlled. */
class AcxExecutorRecoveryConsumerTest {
    private static final String NAME = "executor_recovery";
    private static final String SCRIPT = """
            {"name":"executor_recovery","version":"1","steps":[
              {"id":"act","block":"async_action"},
              {"id":"after","block":"consume","params":{"value":"$act.value"}}
            ]}
            """;
    @Test
    void rejectedResumeThenDonePublishesSuccessWithoutStaleExecutorError() {
        Fixture f = new Fixture();
        AcxRunRecord rejected = f.startAndReject();
        f.resume();
        assertAll("DONE must recover the same consumer, not merely replace its record",
                () -> assertNotSame(rejected, f.session.record()),
                () -> f.record(f.session.record(), AcxStatus.SUCCESS, 2),
                () -> assertNull(f.session.error(), "a normal record must retire the prior executor error"),
                () -> f.query(f.status(), f.session.record()),
                () -> assertEquals(List.of(3, 2, 1, 1, 1, 0), f.counts()),
                () -> f.consumedOnce());
    }
    @Test
    void rejectedResumeThenRunningPublishesResumablePauseBeforeDone() {
        Fixture f = new Fixture();
        AcxRunRecord rejected = f.startAndReject();
        f.host.running = true;
        f.resume();
        AcxRunRecord paused = f.session.record();
        Throwable pausedError = f.session.error();
        Map<String, Object> pausedQuery = f.status();
        List<Integer> pausedCounts = f.counts();
        int purged = f.sessions.purgeFinished();
        AcxSessionManager.Session retained = f.sessions.session(f.session.runId());
        int pendingAfterPurge = f.adapter.pendingCount();
        // Preserve intermediate evidence and attempt DONE even when the stale error caused eviction.
        f.host.running = false;
        Map<String, Object> resumed = f.facade.resumeBound(Map.of("run_id", f.session.runId()), f.binding);
        assertAll("RUNNING probe must recover PAUSED, remain resumable, then consume DONE once",
                () -> assertNotSame(rejected, paused, "RUNNING must produce a fresh real pause record"),
                () -> f.record(paused, AcxStatus.PAUSED, 0),
                () -> assertNull(pausedError, "even a normal PAUSED record must retire the executor error"),
                () -> f.query(pausedQuery, paused),
                () -> assertEquals(List.of(3, 2, 1, 1, 0, 1), pausedCounts),
                () -> assertEquals(0, purged, "a recovered pause must not be evicted as an error"),
                () -> assertSame(f.session, retained),
                () -> assertEquals(1, pendingAfterPurge),
                () -> assertEquals(Boolean.TRUE, resumed.get("success"), resumed.toString()),
                () -> assertNotSame(paused, f.session.record()),
                () -> f.record(f.session.record(), AcxStatus.SUCCESS, 2),
                () -> assertNull(f.session.error()),
                () -> f.query(f.status(), f.session.record()),
                () -> assertEquals(List.of(4, 3, 1, 2, 1, 0), f.counts()),
                () -> f.consumedOnce());
    }
    private static final class Fixture {
        final AcxRegistry registry = new AcxRegistry();
        final ControlledExecutor executor = new ControlledExecutor();
        final ControlledHost host = new ControlledHost();
        final RecordingConsumer consumer = new RecordingConsumer();
        final PortToolAdapter adapter = PortToolAdapter.builder(host).probe(host).build();
        final AcxRunBinding binding = new AcxRunBinding("executor-recovery-consumer", new Object());
        final AcxDefinition definition;
        final AcxRunner runner;
        final AcxSessionManager sessions;
        final AcxFacade facade;
        AcxSessionManager.Session session;
        Fixture() {
            registry.register(adapter);
            registry.register(consumer);
            assertTrue(adapter.schema().outputFields().contains("value"));
            assertEquals(AcxToolSchema.ParamType.INTEGER, consumer.schema().params().get("value"));
            definition = AcxLoader.parseJson(SCRIPT, registry);
            runner = AcxRunner.builder().tools(registry).build();
            sessions = new AcxSessionManager(runner, executor);
            facade = new AcxFacade(sessions, name -> NAME.equals(name) ? definition : null, registry, null);
        }
        AcxRunRecord startAndReject() {
            Map<String, Object> accepted = data(facade.executeBound(Map.of("ac_name", NAME), binding));
            session = sessions.session(assertInstanceOf(String.class, accepted.get("run_id")));
            assertNotNull(session);
            assertEquals("RUNNING", accepted.get("status"), "execute response describes acceptance only");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            AcxRunRecord original = session.record();
            assertAll("accepted task-1 is pending, not completed",
                    () -> record(original, AcxStatus.PAUSED, 0),
                    () -> assertEquals("async_accepted", original.pausedReason()),
                    () -> assertNull(session.error()),
                    () -> query(status(), original),
                    () -> assertEquals(List.of(1, 1, 1, 0, 0, 1), counts()),
                    () -> assertEquals(session.runId(), host.original.runId()),
                    () -> assertEquals(NAME, host.original.acName()),
                    () -> assertEquals(AcxFingerprint.of(definition), host.original.fingerprint()),
                    () -> assertEquals("act", host.original.stepId()));
            executor.rejectNext = true;
            resume();
            Map<String, Object> failedQuery = status();
            assertAll("execute rejects before its Runnable enters the runner",
                    () -> assertSame(original, session.record(), "the original PAUSED record must survive rejection"),
                    () -> assertSame(executor.rejection, session.error()),
                    () -> assertFalse(session.isRunning()),
                    () -> assertEquals(1L, session.attemptId()),
                    () -> assertEquals(Boolean.FALSE, failedQuery.get("success"), failedQuery.toString()),
                    () -> assertEquals(List.of(2, 1, 1, 0, 0, 1), counts()));
            return original;
        }
        void resume() {
            int checkpoint = session.record().completedStepIndex();
            Map<String, Object> accepted = data(facade.resumeBound(Map.of("run_id", session.runId()), binding));
            assertAll("resume acceptance is unchanged even when executor.execute rejects",
                    () -> assertEquals(session.runId(), accepted.get("run_id")),
                    () -> assertEquals("RUNNING", accepted.get("state")),
                    () -> assertEquals(Boolean.TRUE, accepted.get("async")),
                    () -> assertEquals(checkpoint, accepted.get("resumed_from_step")));
        }
        Map<String, Object> status() {
            return facade.statusBound(Map.of("run_id", session.runId()), binding);
        }
        void record(AcxRunRecord actual, AcxStatus expected, int checkpoint) {
            assertNotNull(actual);
            assertAll("real session and runner record",
                    () -> assertSame(session, sessions.session(session.runId())),
                    () -> assertSame(definition, session.definition()),
                    () -> assertTrue(session.matchesBinding(binding)),
                    () -> assertSame(binding.bodyToken(), session.bodyToken()),
                    () -> assertFalse(session.isRunning()),
                    () -> assertFalse(runner.isRunning(session.runId())),
                    () -> assertEquals(session.runId(), actual.runId()),
                    () -> assertEquals(NAME, actual.acName()),
                    () -> assertEquals("1", actual.acVersion()),
                    () -> assertEquals(AcxFingerprint.of(definition), actual.fingerprint()),
                    () -> assertEquals(expected, actual.status(), actual.errorMessage()),
                    () -> assertEquals(checkpoint, actual.completedStepIndex()));
        }
        void query(Map<String, Object> response, AcxRunRecord record) {
            Map<String, Object> expected = new LinkedHashMap<>(record.toMap());
            expected.put("state", record.status().name());
            assertAll("Facade status must expose the recovered record, not stale ERROR",
                    () -> assertEquals(Boolean.TRUE, response.get("success"), response.toString()),
                    () -> assertEquals(expected, response.get("data")));
        }
        List<Integer> counts() {
            // execute attempts / entered Runnables / dispatches / probes / consumptions / pending receipts
            return List.of(executor.attempts, executor.invoked, host.dispatched.size(),
                    host.probed.size(), consumer.consumed.size(), adapter.pendingCount());
        }
        void consumedOnce() {
            assertEquals(List.of(new Consumption(session.runId(), "after", 42)), consumer.consumed);
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        return (Map<String, Object>) assertInstanceOf(Map.class, response.get("data"));
    }
    private static final class ControlledExecutor implements Executor {
        final RejectedExecutionException rejection = new RejectedExecutionException("reject this resume once");
        boolean rejectNext;
        int attempts;
        int invoked;
        @Override public void execute(Runnable command) {
            attempts++;
            if (rejectNext) {
                rejectNext = false;
                throw rejection;
            }
            invoked++;
            command.run();
        }
    }
    private record Identity(String runId, String acName, String fingerprint, String path, String stepId, int iteration) {
        static Identity of(AcxCallContext ctx) {
            return new Identity(ctx.runId(), ctx.acName(), ctx.definitionFingerprint(),
                    ctx.invocationPath(), ctx.stepId(), ctx.loopIter());
        }
    }
    private record Receipt(String taskId, Identity identity) { }
    private record Consumption(String runId, String stepId, Object value) { }
    private static final class RecordingConsumer implements AcxTool {
        final List<Consumption> consumed = new ArrayList<>();
        @Override public String name() { return "consume"; }
        @Override public AcxToolSchema schema() {
            return AcxToolSchema.builder().param("value", AcxToolSchema.ParamType.INTEGER).required("value").build();
        }
        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            consumed.add(new Consumption(ctx.runId(), ctx.stepId(), params.get("value")));
            return AcxStepOutcome.success(Map.of("value", params.get("value")));
        }
    }
    private static final class ControlledHost implements AcxToolPort, AcxCompletionProbe {
        final List<Receipt> dispatched = new ArrayList<>();
        final List<Receipt> probed = new ArrayList<>();
        Identity original;
        boolean running;
        @Override public String name() { return "async_action"; }
        @Override public AcxPortSchema schema() { return new AcxPortSchema(Map.of(), Set.of("value"), false); }
        @Override public Result invoke(Map<String, Object> params) { throw new AssertionError("run context required"); }
        @Override public Result invoke(Map<String, Object> params, AcxCallContext ctx) {
            assertFalse(ctx.isResuming(), "the accepted action must not be re-dispatched");
            assertTrue(params.isEmpty());
            original = Identity.of(ctx);
            dispatched.add(new Receipt("task-1", original));
            return Result.accepted("task-1", Map.of(), "accepted, not completed");
        }
        @Override public Snapshot probe(String taskId) { throw new AssertionError("run context required"); }
        @Override public Snapshot probe(String taskId, AcxCallContext ctx) {
            assertTrue(ctx.isResuming());
            assertEquals("task-1", taskId);
            assertEquals(original, Identity.of(ctx), "probe must retain run/definition/path/step identity");
            probed.add(new Receipt(taskId, Identity.of(ctx)));
            return running ? Snapshot.running("task-1 still running") : Snapshot.done(Map.of("value", 42), "task-1 done");
        }
    }
}
