package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxCompletionProbe;
import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxEvent;
import com.dwinovo.numen.acx.api.AcxExperience;
import com.dwinovo.numen.acx.api.AcxPortSchema;
import com.dwinovo.numen.acx.api.AcxRunBinding;
import com.dwinovo.numen.acx.api.AcxRunRecord;
import com.dwinovo.numen.acx.api.AcxStatus;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import com.dwinovo.numen.acx.api.AcxTool;
import com.dwinovo.numen.acx.api.AcxToolPort;
import com.dwinovo.numen.acx.api.AcxToolSchema;
import com.dwinovo.numen.plugins.acx.AcxRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Real terminal observation boundaries; local ledger only, no Minecraft effects certified. */
class AcxTerminalObservationConsumerTest {
    private static final String NAME = "terminal_observation";
    private static final String SCRIPT = """
            {"name":"terminal_observation","version":"1","steps":[
              {"id":"act","block":"async_action"},
              {"id":"observe","block":"observe","params":{"value":"$act.value"}}
            ]}
            """;

    @Test
    void twoRunningResumesDoNotPublishSuccessUntilDoneAndCompletedQueriesCannotReplayIt() {
        Fixture f = new Fixture(false);
        String run = f.start();
        f.paused(run, 1, 0);
        // Exactly two resumes see RUNNING: three PAUSED attempts are not three business completions.
        for (int i = 1; i <= 2; i++) {
            f.resume(run);
            f.paused(run, i + 1, i);
        }
        f.host.snapshot = AcxCompletionProbe.Snapshot.done(Map.of("value", 42), "done");
        f.resume(run);
        f.succeeded(run, 3, 4);
        f.completedQueriesCannotReplay(run);
    }

    @Test
    void runtimeFailuresAfterActualObservationCannotUndoSuccessOrCauseAnotherDelivery() {
        Fixture f = new Fixture(true);
        String run = f.start();
        f.paused(run, 1, 0);
        f.host.snapshot = AcxCompletionProbe.Snapshot.done(Map.of("value", 42), "done");
        f.resume(run);
        // Each boundary saved its actual argument before throwing an ordinary runtime exception.
        f.succeeded(run, 1, 2);
        f.completedQueriesCannotReplay(run);
    }

    private static final class Fixture {
        final Host host = new Host();
        final Observer consumer = new Observer();
        final AcxQualityLedger ledger = new AcxQualityLedger();
        final List<AcxRunRecord> records = new ArrayList<>();
        final List<AcxExperience> experiences = new ArrayList<>();
        final List<AcxEvent> events = new ArrayList<>();
        final AcxRunBinding binding = new AcxRunBinding("terminal-observer", new Object());
        final PortToolAdapter adapter = PortToolAdapter.builder(host).probe(host).build();
        final AcxDefinition definition;
        final AcxRunner runner;
        final AcxSessionManager sessions;
        final AcxFacade facade;
        Fixture(boolean faults) {
            AcxRegistry registry = new AcxRegistry();
            registry.register(adapter);
            registry.register(consumer);
            definition = AcxLoader.parseJson(SCRIPT, registry);
            runner = AcxRunner.builder().tools(registry).store(record -> {
                ledger.append(record);
                records.add(record);
                if (faults) throw new IllegalStateException("record observer failed after append");
            }).experienceSink(actualAcxExperience -> {
                experiences.add(actualAcxExperience);
                if (faults) throw new IllegalStateException("experience observer failed after save");
            }).events(event -> {
                events.add(event);
                if (faults && event.kind() == AcxEvent.Kind.RUN_FINISHED)
                    throw new RuntimeException("finish observer failed after save");
            }).build();
            sessions = new AcxSessionManager(runner, Runnable::run);
            facade = new AcxFacade(sessions, name -> NAME.equals(name) ? definition : null, registry, null);
        }
        String start() {
            Map<String, Object> accepted = data(facade.executeBound(Map.of("ac_name", NAME), binding));
            assertEquals("RUNNING", accepted.get("status"), "acceptance is not final status");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            return assertInstanceOf(String.class, accepted.get("run_id"));
        }
        void resume(String run) {
            Map<String, Object> accepted = data(facade.resumeBound(Map.of("run_id", run), binding));
            assertEquals(run, accepted.get("run_id"));
            assertEquals("RUNNING", accepted.get("state"), "resume acceptance is not completion");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            assertEquals(0, accepted.get("resumed_from_step"));
        }
        AcxRunRecord state(String run, AcxStatus status, int checkpoint) {
            AcxSessionManager.Session session = sessions.session(run);
            assertNotNull(session);
            assertTrue(session.matchesBinding(binding));
            assertSame(binding.bodyToken(), session.bodyToken());
            assertFalse(session.isRunning());
            assertFalse(runner.isRunning(run));
            assertNull(session.error());
            AcxRunRecord record = session.record();
            assertNotNull(record);
            assertSame(record, records.get(records.size() - 1), "store received the original record");
            assertEquals(run, record.runId());
            assertEquals(NAME, record.acName());
            assertEquals("1", record.acVersion());
            assertEquals(AcxFingerprint.of(definition), record.fingerprint());
            assertEquals(status, record.status(), record.errorMessage());
            assertEquals(checkpoint, record.completedStepIndex());
            Map<String, Object> actual = data(facade.statusBound(Map.of("run_id", run), binding));
            assertEquals(status.name(), actual.get("state"));
            record.toMap().forEach((key, value) -> assertEquals(value, actual.get(key), key));
            return record;
        }
        void paused(String run, int attempts, int probes) {
            state(run, AcxStatus.PAUSED, 0);
            assertEquals(List.of(new Call(run, "act", null)), host.dispatches);
            assertEquals(java.util.Collections.nCopies(probes, "task1"), host.probes);
            assertEquals(1, adapter.pendingCount());
            assertTrue(consumer.calls.isEmpty());
            assertTrue(experiences.isEmpty());
            assertEquals(0, ledger.stats(NAME, "1").success());
            assertEquals(attempts, records.size());
            assertTrue(records.stream().allMatch(r -> run.equals(r.runId()) && r.status() == AcxStatus.PAUSED));
            assertEquals(attempts, finishes(run, AcxStatus.PAUSED));
            assertEquals(0, finishes(run, AcxStatus.SUCCESS));
        }
        void succeeded(String run, int probes, int attempts) {
            AcxRunRecord record = state(run, AcxStatus.SUCCESS, 2);
            assertEquals(List.of(new Call(run, "act", null)), host.dispatches);
            assertEquals(java.util.Collections.nCopies(probes, "task1"), host.probes);
            assertEquals(List.of(new Call(run, "observe", 42)), consumer.calls);
            assertEquals(Integer.class, consumer.calls.get(0).value().getClass());
            assertEquals(0, adapter.pendingCount());
            assertEquals(attempts, records.size());
            assertEquals(1, records.stream().filter(r -> run.equals(r.runId()) && r.status() == AcxStatus.SUCCESS).count());
            assertEquals(1, ledger.stats(NAME, "1").success());
            assertEquals(1, experiences.size());
            AcxExperience actual = experiences.get(0);
            assertEquals(NAME, actual.acName());
            assertEquals("1", actual.acVersion());
            assertEquals(record.fingerprint(), actual.fingerprint());
            assertEquals(2, actual.completedSteps());
            assertEquals(record.timestamp(), actual.timestamp());
            assertEquals(1, finishes(run, AcxStatus.SUCCESS), "only SUCCESS finishes this business run");
        }
        long finishes(String run, AcxStatus status) {
            return events.stream().filter(e -> e.kind() == AcxEvent.Kind.RUN_FINISHED
                    && run.equals(e.runId()) && e.status() == status).count();
        }
        List<Integer> counts() {
            return List.of(host.dispatches.size(), host.probes.size(), consumer.calls.size(),
                    ledger.stats(NAME, "1").success(), experiences.size(), records.size(), events.size());
        }
        void completedQueriesCannotReplay(String run) {
            List<Integer> before = counts();
            for (int i = 0; i < 3; i++) state(run, AcxStatus.SUCCESS, 2);
            assertEquals(Boolean.FALSE, facade.resumeBound(Map.of("run_id", run), binding).get("success"));
            state(run, AcxStatus.SUCCESS, 2);
            assertEquals(before, counts(), "queries and rejected resume must not call any observer or dispatch");
            assertEquals(0, adapter.pendingCount());
        }
    }

    private record Call(String runId, String stepId, Object value) { }

    private static final class Host implements AcxToolPort, AcxCompletionProbe {
        final List<Call> dispatches = new ArrayList<>();
        final List<String> probes = new ArrayList<>();
        Snapshot snapshot = Snapshot.running("still running");
        @Override public String name() { return "async_action"; }
        @Override public AcxPortSchema schema() {
            return AcxPortSchema.builder().param("value", AcxPortSchema.Param.opt(AcxPortSchema.Type.INTEGER))
                    .output("value").build();
        }
        @Override public Result invoke(Map<String, Object> params) { throw new IllegalStateException("context required"); }
        @Override public Result invoke(Map<String, Object> params, AcxTool.AcxCallContext ctx) {
            dispatches.add(new Call(ctx.runId(), ctx.stepId(), null));
            return Result.accepted("task1", Map.of(), "accepted, not done");
        }
        @Override public Snapshot probe(String taskId) { probes.add(taskId); return snapshot; }
    }

    private static final class Observer implements AcxTool {
        final List<Call> calls = new ArrayList<>();
        @Override public String name() { return "observe"; }
        @Override public AcxToolSchema schema() {
            return new AcxToolSchema(Map.of("value", AcxToolSchema.ParamType.INTEGER), Set.of("value"));
        }
        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            calls.add(new Call(ctx.runId(), ctx.stepId(), params.get("value")));
            return AcxStepOutcome.success(Map.of());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        return (Map<String, Object>) assertInstanceOf(Map.class, response.get("data"));
    }
}
