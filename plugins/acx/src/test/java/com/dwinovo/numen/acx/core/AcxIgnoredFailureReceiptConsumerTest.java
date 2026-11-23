package com.dwinovo.numen.acx.core;
import com.dwinovo.numen.acx.api.*;
import com.dwinovo.numen.acx.api.AcxTool.AcxCallContext;
import com.dwinovo.numen.plugins.acx.AcxRegistry;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
/** Real lifecycle only: ignoring known failure is not business success or evidence the body stopped. */
class AcxIgnoredFailureReceiptConsumerTest {
    private static final String NAME = "ignored_failure_receipt";
    // withFailureDiag emits _failed (Boolean), not an invented _ignored_failure field.
    private static final String JSON = """
            {"name":"ignored_failure_receipt","version":"1","steps":[
              {"id":"act","block":"late_failure","params":{"ignore_failure":true}},
              {"id":"after","block":"consume","params":{"reason":"$act.failure_reason","ignored":"$act._failed"}}
            ]}
            """;
    @Test
    void cancelledLateFailureIsRetainedUntilSameRunResumeConsumesOriginalDiagnosis() {
        Fixture f = new Fixture(true);
        String runId = f.start();
        f.resume();
        assertEquals("host_cancelled", f.state(AcxStatus.PAUSED, 0).pausedReason());
        assertFalse(f.originalSession.isCancelRequested(), "the finished attempt clears its request flag; the record retains cancellation");
        assertEquals(1, f.host.cancels);
        f.counts(1, 0, 1);
        assertTrue(f.host.failure.isFailed(), "the original reply is FAILED, never COMPLETED");
        // Explicit resume consumes saved terminal failure: no second invoke, poll or probe.
        f.resume();
        f.state(AcxStatus.SUCCESS, 2);
        f.counts(1, 1, 0);
        assertEquals(1, f.host.cancels);
        f.consumedOriginalDiagnosis(runId);
    }
    @Test
    void uncancelledLateFailureIsIgnoredOnlyAfterKnownReplyAndConsumedOnce() {
        Fixture f = new Fixture(false);
        String runId = f.start();
        f.resume();
        f.state(AcxStatus.SUCCESS, 2);
        f.counts(1, 1, 0);
        assertEquals(0, f.host.cancels);
        f.consumedOriginalDiagnosis(runId);
    }
    private static final class Fixture {
        final AcxRegistry registry = new AcxRegistry();
        final RecordingConsumer consumer = new RecordingConsumer();
        final AcxRunBinding binding = new AcxRunBinding("ignored-failure-owner", new Object());
        final ControlledHost host;
        final PortToolAdapter adapter;
        final AcxDefinition definition;
        final AcxRunner runner;
        AcxSessionManager sessions;
        final AcxFacade facade;
        AcxSessionManager.Session originalSession;
        Fixture(boolean cancelOnPoll) {
            host = new ControlledHost(cancelOnPoll, () -> sessions);
            adapter = PortToolAdapter.builder(host).probe(host).build();
            registry.register(adapter);
            registry.register(consumer);
            definition = AcxLoader.parseJson(JSON, registry);
            runner = AcxRunner.builder().tools(registry).build();
            sessions = new AcxSessionManager(runner, Runnable::run);
            facade = new AcxFacade(sessions, name -> NAME.equals(name) ? definition : null, registry, null);
        }
        String start() {
            Map<String, Object> accepted = data(facade.executeBound(Map.of("ac_name", NAME), binding));
            assertEquals("RUNNING", accepted.get("status"), "acceptance is not run completion");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            String runId = assertInstanceOf(String.class, accepted.get("run_id"));
            originalSession = sessions.session(runId);
            assertEquals("host_reply_pending", state(AcxStatus.PAUSED, 0).pausedReason());
            counts(0, 0, 1);
            assertNull(host.failure, "unknown is not an ignorable known failure");
            assertEquals(runId, host.context.runId());
            assertEquals(NAME, host.context.acName());
            assertEquals(AcxFingerprint.of(definition), host.context.definitionFingerprint());
            assertEquals("act", host.context.stepId());
            assertFalse(host.context.invocationPath().isEmpty());
            assertEquals(0, host.context.loopIter());
            return runId;
        }
        void resume() {
            long attempt = originalSession.attemptId();
            int checkpoint = originalSession.record().completedStepIndex();
            Map<String, Object> accepted = data(facade.resumeBound(Map.of("run_id", originalSession.runId()), binding));
            assertEquals(originalSession.runId(), accepted.get("run_id"));
            assertEquals("RUNNING", accepted.get("state"));
            assertEquals(Boolean.TRUE, accepted.get("async"));
            assertEquals(checkpoint, accepted.get("resumed_from_step"));
            assertEquals(attempt + 1, originalSession.attemptId());
        }
        AcxRunRecord state(AcxStatus expected, int checkpoint) {
            assertNotNull(originalSession);
            assertSame(originalSession, sessions.session(originalSession.runId()));
            assertSame(definition, originalSession.definition());
            assertTrue(originalSession.matchesBinding(binding));
            assertSame(binding.bodyToken(), originalSession.bodyToken());
            assertFalse(originalSession.isRunning());
            assertFalse(runner.isRunning(originalSession.runId()));
            assertNull(originalSession.error());
            AcxRunRecord record = originalSession.record();
            assertNotNull(record);
            assertEquals(originalSession.runId(), record.runId());
            assertEquals(NAME, record.acName());
            assertEquals("1", record.acVersion());
            assertEquals(AcxFingerprint.of(definition), record.fingerprint());
            assertEquals(expected, record.status(), record.errorMessage());
            assertEquals(checkpoint, record.completedStepIndex());
            Map<String, Object> expectedData = new LinkedHashMap<>(record.toMap());
            expectedData.put("state", expected.name());
            assertEquals(expectedData, data(facade.statusBound(Map.of("run_id", record.runId()), binding)));
            return record;
        }
        void counts(int polls, int consumed, int pending) {
            assertEquals(1, host.invokes);
            assertEquals(polls, host.polls);
            assertEquals(0, host.probes);
            assertEquals(consumed, consumer.consumed.size());
            assertEquals(pending, adapter.pendingCount());
        }
        void consumedOriginalDiagnosis(String runId) {
            assertTrue(host.failure.isFailed());
            assertEquals("known failure", host.failure.message());
            Consumption actual = consumer.consumed.get(0);
            assertEquals(runId, actual.runId());
            assertEquals("after", actual.stepId());
            String reason = assertInstanceOf(String.class, actual.reason());
            assertTrue(reason.contains(host.failure.message()), reason); // Adapter adds its failure prefix.
            assertEquals(Boolean.TRUE, assertInstanceOf(Boolean.class, actual.ignored()));
            assertEquals(List.of(true), consumer.resumeModes);
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        return (Map<String, Object>) assertInstanceOf(Map.class, response.get("data"));
    }
    private record Consumption(String runId, String stepId, Object reason, Object ignored) { }
    private static final class RecordingConsumer implements AcxTool {
        final List<Consumption> consumed = new ArrayList<>();
        final List<Boolean> resumeModes = new ArrayList<>();
        @Override public String name() { return "consume"; }
        @Override public AcxToolSchema schema() {
            return AcxToolSchema.builder().param("reason", AcxToolSchema.ParamType.STRING).required("reason")
                    .param("ignored", AcxToolSchema.ParamType.BOOLEAN).required("ignored").build();
        }
        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            consumed.add(new Consumption(ctx.runId(), ctx.stepId(), params.get("reason"), params.get("ignored")));
            resumeModes.add(ctx.isResuming());
            return AcxStepOutcome.success(params);
        }
    }
    private static final class ControlledHost implements AcxToolPort, AcxCompletionProbe {
        final boolean cancelOnPoll;
        final Supplier<AcxSessionManager> sessions;
        int invokes, polls, probes, cancels;
        AcxCallContext context;
        Result failure;
        ControlledHost(boolean cancelOnPoll, Supplier<AcxSessionManager> sessions) {
            this.cancelOnPoll = cancelOnPoll;
            this.sessions = sessions;
        }
        @Override public String name() { return "late_failure"; }
        @Override public AcxPortSchema schema() { return new AcxPortSchema(Map.of(), Set.of(), false); }
        @Override public Result invoke(Map<String, Object> params) { throw new AssertionError("real context required"); }
        @Override public Result invoke(Map<String, Object> params, AcxCallContext ctx) {
            assertEquals(1, ++invokes);
            context = ctx;
            assertFalse(ctx.isResuming());
            assertTrue(ctx.defersReceiptConsumption() && ctx.defersIgnoredFailureReceiptConsumption());
            assertTrue(params.isEmpty(), "ignore_failure is runner control, not a host parameter");
            throw new ReplyPendingAfterDispatchException("original reply unknown", () -> {
                assertEquals(1, ++polls, "saved terminal failure must never poll the original closure twice");
                assertTrue(sessions.get().session(ctx.runId()).isRunning());
                assertEquals(1L, sessions.get().session(ctx.runId()).attemptId());
                failure = Result.failed("known failure");
                if (cancelOnPoll) {
                    assertTrue(sessions.get().cancel(ctx.runId()));
                    cancels++;
                }
                return Optional.of(failure);
            });
        }
        @Override public Snapshot probe(String taskId) { probes++; throw new AssertionError("no task id to probe"); }
        @Override public Snapshot probe(String taskId, AcxCallContext ctx) { return probe(taskId); }
    }
}
