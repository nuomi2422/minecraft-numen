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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 019: settle the original call before new intent; only host/probe/consumer are controlled.
 * This is an in-memory receipt boundary check, not persistence or proof of world effects. */
class AcxPendingParameterConsumerTest {
    private static final String NAME = "pending_parameter_consumer";
    private static final String SCRIPT = """
            {"name":"pending_parameter_consumer","version":"1","steps":[
              {"id":"action","block":"pending_action","params":{"target":"$input.target"}},
              {"id":"consume","block":"observe_result","params":{"target":"$action.target"}}
            ]}
            """;

    @Test
    void acceptedOriginalTaskIgnoresReplacementInputUntilSettledAndNewRunGetsNewTarget() {
        Fixture f = new Fixture(false);
        String originalRun = f.start("old");
        Dispatch original = f.host.dispatched.get(0);
        assertEquals(new Dispatch(originalRun, "s0/", "old", "task-1"), original);
        f.counts(1, 0, 0, 1);

        f.resume(originalRun, "new");
        f.state(originalRun, AcxStatus.PAUSED, 0, "new");
        f.counts(1, 1, 0, 1);
        assertEquals(List.of(original), f.host.probed, "RUNNING must query the original task/run/call");

        f.host.doneSet.add(original.taskId());
        f.resume(originalRun, "new");
        f.state(originalRun, AcxStatus.SUCCESS, 2, "new");
        f.counts(1, 2, 1, 0);
        assertEquals(List.of(original, original), f.host.probed);
        assertEquals(List.of(new Consumption(originalRun, "old")), f.consumer.consumed);

        String freshRun = f.start("new");
        assertNotEquals(originalRun, freshRun, "a new target starts a new intent/run");
        Dispatch fresh = f.host.dispatched.get(1);
        assertEquals(new Dispatch(freshRun, original.path(), "new", "task-2"), fresh);
        f.counts(2, 2, 1, 1);
        f.host.doneSet.add(fresh.taskId());
        f.resume(freshRun, "new");
        f.state(freshRun, AcxStatus.SUCCESS, 2, "new");
        f.counts(2, 3, 2, 0);
        assertEquals(List.of(original, original, fresh), f.host.probed);
        assertEquals(List.of(new Consumption(originalRun, "old"), new Consumption(freshRun, "new")),
                f.consumer.consumed, "raw downstream arguments must remain attributed to their own runs");
        f.state(originalRun, AcxStatus.SUCCESS, 2, "new");
    }

    @Test
    void unknownDispatchReplyKeepsOriginalReceiptWithoutInventingTaskOrReinvokingWithNewInput() {
        Fixture f = new Fixture(true);
        String run = f.start("old");
        Dispatch original = f.host.dispatched.get(0);
        assertEquals(new Dispatch(run, "s0/", "old", ""), original);
        assertEquals(0, f.host.receiptPolls);
        f.counts(1, 0, 0, 1);

        f.resume(run, "new");
        AcxRunRecord waiting = f.state(run, AcxStatus.PAUSED, 0, "new");
        assertEquals("host_reply_pending", waiting.pausedReason());
        assertEquals(1, f.host.receiptPolls);
        f.counts(1, 0, 0, 1);

        f.host.reply = AcxToolPort.Result.completed(Map.of("target", original.target()));
        assertEquals("", f.host.reply.taskId(), "a completed reply needs no invented task identity");
        f.resume(run, "new");
        f.state(run, AcxStatus.SUCCESS, 2, "new");
        assertEquals(2, f.host.receiptPolls, "both resumes must poll the original closure");
        assertEquals(List.of(original), f.host.dispatched, "unknown side effects cannot authorize redispatch");
        assertEquals(List.of(new Consumption(run, "old")), f.consumer.consumed);
        f.counts(1, 0, 1, 0);
    }

    private static final class Fixture {
        final AcxRegistry registry = new AcxRegistry();
        final ControlledHost host;
        final RecordingConsumer consumer = new RecordingConsumer();
        final PortToolAdapter adapter;
        final AcxRunBinding binding = new AcxRunBinding("pending-parameter-consumer", new Object());
        final AcxDefinition definition;
        final AcxRunner runner;
        final AcxSessionManager sessions;
        final AcxFacade facade;

        Fixture(boolean replyPending) {
            host = new ControlledHost(replyPending);
            adapter = PortToolAdapter.builder(host).probe(host).build();
            registry.register(adapter);
            registry.register(consumer);
            assertEquals(AcxToolSchema.ParamType.STRING, adapter.schema().params().get("target"));
            assertEquals(AcxToolSchema.ParamType.STRING, consumer.schema().params().get("target"));
            definition = AcxLoader.parseJson(SCRIPT, registry);
            runner = AcxRunner.builder().tools(registry).build();
            sessions = new AcxSessionManager(runner, Runnable::run);
            facade = new AcxFacade(sessions, name -> NAME.equals(name) ? definition : null, registry, null);
        }

        String start(String target) {
            Map<String, Object> accepted = data(facade.executeBound(
                    Map.of("ac_name", NAME, "input", Map.of("target", target)), binding));
            assertEquals(NAME, accepted.get("ac_name"));
            assertEquals("RUNNING", accepted.get("status"), "accepted does not mean completed");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            String run = assertInstanceOf(String.class, accepted.get("run_id"));
            state(run, AcxStatus.PAUSED, 0, target);
            return run;
        }

        void resume(String run, String target) {
            AcxSessionManager.Session original = sessions.session(run);
            Map<String, Object> accepted = data(facade.resumeBound(
                    Map.of("run_id", run, "input", Map.of("target", target)), binding));
            assertEquals(run, accepted.get("run_id"));
            assertEquals("RUNNING", accepted.get("state"), "resume acceptance is not terminal status");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            assertEquals(0, accepted.get("resumed_from_step"));
            assertSame(original, sessions.session(run));
        }

        AcxRunRecord state(String run, AcxStatus expected, int checkpoint, String inputTarget) {
            AcxSessionManager.Session session = sessions.session(run);
            assertNotNull(session);
            assertSame(definition, session.definition());
            assertTrue(session.matchesBinding(binding));
            assertSame(binding.bodyToken(), session.bodyToken());
            assertFalse(session.isRunning());
            assertFalse(runner.isRunning(run));
            assertNull(session.error());
            AcxRunRecord record = session.record();
            assertNotNull(record);
            assertEquals(run, record.runId());
            assertEquals(expected, record.status(), record.errorMessage());
            assertEquals(checkpoint, record.completedStepIndex());
            assertEquals(Map.of("target", inputTarget), record.input(), "public resume input really changed");
            Map<String, Object> status = new LinkedHashMap<>(record.toMap());
            status.put("state", expected.name());
            assertEquals(status, data(facade.statusBound(Map.of("run_id", run), binding)));
            return record;
        }

        void counts(int dispatches, int probes, int consumptions, int pending) {
            assertEquals(dispatches, host.dispatched.size());
            assertEquals(probes, host.probed.size());
            assertEquals(consumptions, consumer.consumed.size());
            assertEquals(pending, adapter.pendingCount());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        return (Map<String, Object>) assertInstanceOf(Map.class, response.get("data"));
    }

    private record Dispatch(String runId, String path, Object target, String taskId) { }
    private record Consumption(String runId, Object actualObject) { }

    private static final class RecordingConsumer implements AcxTool {
        final List<Consumption> consumed = new ArrayList<>();
        @Override public String name() { return "observe_result"; }
        @Override public AcxToolSchema schema() {
            return AcxToolSchema.builder().param("target", AcxToolSchema.ParamType.STRING).required("target").build();
        }
        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            Object actualObject = params.get("target");
            assertInstanceOf(String.class, actualObject);
            consumed.add(new Consumption(ctx.runId(), actualObject));
            return AcxStepOutcome.success(Map.of("target", actualObject));
        }
    }

    private static final class ControlledHost implements AcxToolPort, AcxCompletionProbe {
        final boolean replyPending;
        final List<Dispatch> dispatched = new ArrayList<>();
        final List<Dispatch> probed = new ArrayList<>();
        final Set<String> doneSet = new HashSet<>();
        Result reply;
        int receiptPolls;
        ControlledHost(boolean replyPending) { this.replyPending = replyPending; }
        @Override public String name() { return "pending_action"; }
        @Override public AcxPortSchema schema() {
            return new AcxPortSchema(Map.of("target", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING)),
                    Set.of("target"), false);
        }
        @Override public Result invoke(Map<String, Object> params) { throw new AssertionError("run context required"); }
        @Override public Result invoke(Map<String, Object> params, AcxCallContext ctx) {
            assertFalse(ctx.isResuming(), "pending original calls must not be re-invoked");
            assertEquals(NAME, ctx.acName());
            String task = replyPending ? "" : "task-" + (dispatched.size() + 1);
            Dispatch original = new Dispatch(ctx.runId(), ctx.invocationPath(), params.get("target"), task);
            dispatched.add(original);
            if (replyPending) throw new ReplyPendingAfterDispatchException("original reply unknown", () -> {
                receiptPolls++;
                return Optional.ofNullable(reply);
            });
            return Result.accepted(task, Map.of(), "accepted, not completed");
        }
        @Override public Snapshot probe(String taskId) { throw new AssertionError("run context required"); }
        @Override public Snapshot probe(String taskId, AcxCallContext ctx) {
            assertTrue(ctx.isResuming());
            Dispatch original = dispatched.stream().filter(call -> call.taskId().equals(taskId)).findFirst().orElseThrow();
            assertFalse(taskId.isEmpty(), "unknown replies must not be converted into task probes");
            assertEquals(original.runId(), ctx.runId());
            assertEquals(original.path(), ctx.invocationPath());
            probed.add(new Dispatch(ctx.runId(), ctx.invocationPath(), original.target(), taskId));
            return doneSet.contains(taskId) ? Snapshot.done(Map.of("target", original.target()), "original DONE")
                    : Snapshot.running("original still RUNNING");
        }
    }
}
