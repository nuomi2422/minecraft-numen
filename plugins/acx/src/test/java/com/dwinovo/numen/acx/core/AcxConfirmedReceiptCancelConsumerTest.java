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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LH2b: confirmed child output survives cancellation until an explicit resume consumes it.
 * Loader, registry, catalog, facade, sessions, runner and receipt adapter are production code;
 * only host acceptance/completion are substituted, and consume is an observing JVM tool.
 * These checks establish receipt delivery, not Minecraft effects or body stop/release.
 */
class AcxConfirmedReceiptCancelConsumerTest {
    private static final String PARENT = "confirmed_receipt_parent";
    private static final String CHILD = "confirmed_receipt_child";
    private static final String PARENT_JSON = """
            {"name":"confirmed_receipt_parent","version":"1","steps":[
              {"id":"first","block":"confirmed_receipt_child"},
              {"id":"after","block":"consume","params":{"value":"$first.value"}}
            ]}
            """;
    private static final String CHILD_JSON = """
            {"name":"confirmed_receipt_child","version":"1","steps":[
              {"id":"act","block":"async_action"}
            ]}
            """;

    @Test
    void confirmedDoneThenCancelKeepsReceiptUntilExplicitResumeDeliversValueOnce() {
        Fixture f = new Fixture();
        String runId = f.start();
        List<Boolean> cancelResults = new ArrayList<>();
        f.host.beforeDone = ctx -> {
            assertEquals(runId, ctx.runId());
            assertSame(f.originalSession, f.sessions.session(ctx.runId()));
            cancelResults.add(f.sessions.cancel(ctx.runId()));
            assertEquals(List.of(true), cancelResults, "real in-flight session cancellation must succeed once");
            assertTrue(ctx.isCancelRequested(), "read the real runner cancellation flag, never manufacture it");
        };

        f.resume();
        AcxRunRecord cancelled = f.state(AcxStatus.PAUSED, 0);
        assertEquals("host_cancelled", cancelled.pausedReason());
        assertEquals(List.of(true), cancelResults);
        f.counts(1, 1, 0, 1);

        // The saved DONE output, not another host invocation or probe, must finish this same run.
        f.resume();
        f.state(AcxStatus.SUCCESS, 2);
        f.counts(1, 1, 1, 0);
        assertEquals(List.of(new Consumption(runId, "after", 42)), f.consumer.consumed);
        assertEquals(List.of(true), cancelResults, "using a saved terminal receipt must not call the probe again");
    }

    @Test
    void confirmedDoneWithoutCancelDeliversChildValueOnceAndCommitsBothParentSteps() {
        Fixture f = new Fixture();
        String runId = f.start();

        f.resume();
        f.state(AcxStatus.SUCCESS, 2);
        f.counts(1, 1, 1, 0);
        assertEquals(List.of(new Consumption(runId, "after", 42)), f.consumer.consumed);
    }

    private static final class Fixture {
        final AcxRegistry registry = new AcxRegistry();
        final ControlledHost host = new ControlledHost();
        final RecordingConsumer consumer = new RecordingConsumer();
        final PortToolAdapter adapter = PortToolAdapter.builder(host).probe(host).build();
        final AcxRunBinding binding = new AcxRunBinding("confirmed-receipt-consumer", new Object());
        final AcxDefinition parent;
        final AcxRunner runner;
        final AcxSessionManager sessions;
        final AcxFacade facade;
        AcxSessionManager.Session originalSession;

        Fixture() {
            registry.register(adapter);
            registry.register(consumer);
            assertTrue(adapter.schema().outputFields().contains("value"));
            assertEquals(AcxToolSchema.ParamType.INTEGER, consumer.schema().params().get("value"));
            assertTrue(consumer.schema().outputFields().contains("value"));
            AcxDefinition child = AcxLoader.parseJson(CHILD_JSON, registry);
            parent = AcxLoader.parseJson(PARENT_JSON, registry);
            runner = AcxRunner.builder().tools(registry).catalog(AcxCatalog.of(Map.of(CHILD, child))).build();
            sessions = new AcxSessionManager(runner, Runnable::run);
            // The parent lookup stays unchanged when Facade rechecks it on each explicit resume.
            facade = new AcxFacade(sessions, name -> PARENT.equals(name) ? parent : null, registry, null);
        }

        String start() {
            Map<String, Object> accepted = data(facade.executeBound(Map.of("ac_name", PARENT), binding));
            assertEquals(PARENT, accepted.get("ac_name"));
            assertEquals("1", accepted.get("ac_version"));
            assertEquals("RUNNING", accepted.get("status"), "acceptance is not the resulting run status");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            String runId = assertInstanceOf(String.class, accepted.get("run_id"));
            originalSession = sessions.session(runId);
            assertNotNull(originalSession);
            assertSame(parent, originalSession.definition());
            state(AcxStatus.PAUSED, 0);
            counts(1, 0, 0, 1);
            assertEquals(new Dispatch("task-1", host.originalContext), host.dispatched.get(0));
            assertEquals(runId, host.originalContext.runId());
            assertEquals(PARENT, host.originalContext.acName());
            assertEquals(AcxFingerprint.of(parent), host.originalContext.fingerprint());
            assertEquals("act", host.originalContext.stepId());
            assertTrue(host.originalContext.path().contains("ac:"), "the host must be invoked inside the real child AC");
            return runId;
        }

        void resume() {
            String runId = originalSession.runId();
            int resumedFrom = originalSession.record().completedStepIndex();
            Map<String, Object> accepted = data(facade.resumeBound(Map.of("run_id", runId), binding));
            assertEquals(runId, accepted.get("run_id"));
            assertEquals("RUNNING", accepted.get("state"), "resume acceptance must not stand in for terminal status");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            assertEquals(resumedFrom, accepted.get("resumed_from_step"));
        }

        AcxRunRecord state(AcxStatus expected, int checkpoint) {
            String runId = originalSession.runId();
            assertSame(originalSession, sessions.session(runId), "resume must keep the original Session and run id");
            assertTrue(originalSession.matchesBinding(binding));
            assertSame(binding.bodyToken(), originalSession.bodyToken());
            assertFalse(originalSession.isRunning(), "the synchronous executor has returned from this attempt");
            assertFalse(runner.isRunning(runId));
            assertNull(originalSession.error());
            AcxRunRecord record = originalSession.record();
            assertNotNull(record);
            assertEquals(runId, record.runId());
            assertEquals(PARENT, record.acName());
            assertEquals("1", record.acVersion());
            assertEquals(AcxFingerprint.of(parent), record.fingerprint());
            assertEquals(expected, record.status(), record.errorMessage());
            assertEquals(checkpoint, record.completedStepIndex());
            Map<String, Object> actual = data(facade.statusBound(Map.of("run_id", runId), binding));
            Map<String, Object> expectedData = new LinkedHashMap<>(record.toMap());
            expectedData.put("state", expected.name());
            assertEquals(expectedData, actual, "Facade status must expose the real record, not its RUNNING acceptance");
            return record;
        }

        void counts(int dispatched, int probed, int consumed, int pending) {
            assertAll("dispatch / probe / actual downstream consumption / pending receipts",
                    () -> assertEquals(dispatched, host.dispatched.size()),
                    () -> assertEquals(probed, host.probed.size()),
                    () -> assertEquals(consumed, consumer.consumed.size()),
                    () -> assertEquals(pending, adapter.pendingCount()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        assertInstanceOf(Map.class, response.get("data"));
        return (Map<String, Object>) response.get("data");
    }

    private record CallIdentity(String runId, String acName, String fingerprint, String path, String stepId, int iteration) {
        static CallIdentity of(AcxCallContext ctx) {
            return new CallIdentity(ctx.runId(), ctx.acName(), ctx.definitionFingerprint(),
                    ctx.invocationPath(), ctx.stepId(), ctx.loopIter());
        }
    }

    private record Dispatch(String taskId, CallIdentity context) { }
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

    /** Only the host boundary is controlled; neither run flags nor recovery state are injected. */
    private static final class ControlledHost implements AcxToolPort, AcxCompletionProbe {
        final List<Dispatch> dispatched = new ArrayList<>();
        final List<Dispatch> probed = new ArrayList<>();
        CallIdentity originalContext;
        Consumer<AcxCallContext> beforeDone = ctx -> { };

        @Override public String name() { return "async_action"; }

        @Override public AcxPortSchema schema() {
            return new AcxPortSchema(Map.of(), Set.of("value"), false);
        }

        @Override public Result invoke(Map<String, Object> params) {
            throw new AssertionError("host dispatch must receive the real run context");
        }

        @Override public Result invoke(Map<String, Object> params, AcxCallContext ctx) {
            assertNotNull(ctx);
            assertFalse(ctx.isResuming(), "an accepted child action must never be re-dispatched on resume");
            assertTrue(params.isEmpty());
            originalContext = CallIdentity.of(ctx);
            dispatched.add(new Dispatch("task-1", originalContext));
            return Result.accepted("task-1", Map.of(), "original asynchronous action accepted, not completed");
        }

        @Override public Snapshot probe(String taskId) {
            throw new AssertionError("completion probe must receive the original run context");
        }

        @Override public Snapshot probe(String taskId, AcxCallContext ctx) {
            assertNotNull(ctx);
            assertTrue(ctx.isResuming(), "completion evidence is read only on explicit resume");
            assertEquals("task-1", taskId);
            assertEquals(originalContext, CallIdentity.of(ctx), "task/run/definition/child call-site identity must match");
            probed.add(new Dispatch(taskId, CallIdentity.of(ctx)));
            Snapshot done = Snapshot.done(Map.of("value", 42), "the original accepted task is DONE");
            beforeDone.accept(ctx);
            return done;
        }
    }
}
