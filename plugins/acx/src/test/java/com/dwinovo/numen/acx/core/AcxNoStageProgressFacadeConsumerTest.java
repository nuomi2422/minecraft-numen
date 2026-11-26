package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxBodyAction;
import com.dwinovo.numen.acx.api.AcxCompletionProbe;
import com.dwinovo.numen.acx.api.AcxDefinition;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class AcxNoStageProgressFacadeConsumerTest {
    private static final String NAME = "no_stage_progress_consumer";
    private static final String SCRIPT = """
            {"name":"no_stage_progress_consumer","version":"1","steps":[
              {"id":"act","block":"long_action"},
              {"id":"after","block":"consume_result"}
            ]}
            """;
    private static final AcxBodyAction.Ref ACTION = new AcxBodyAction.Ref(
            "task-1", "call-1", "long_action", "owner", "world", "session", 1);

    @Test
    void unchangedRunningFactsPauseWithoutCancellingOrRedispatchingAndOriginalReceiptCanStillFinish() {
        Fixture f = new Fixture();
        String run = f.start();
        f.clock.set(29_000);
        f.resume(run);
        assertEquals("body_active", f.session.record().pausedReason());
        f.clock.set(30_000);
        f.resume(run);
        AcxRunRecord paused = f.session.record();
        assertEquals(AcxStatus.PAUSED, paused.status());
        assertEquals("acx_no_stage_progress", paused.pausedReason());
        assertEquals(0, paused.completedStepIndex());
        assertEquals(1, f.adapter.pendingCount());
        assertEquals(1, f.host.dispatches);

        f.resume(run);
        assertEquals("acx_no_stage_progress", f.session.record().pausedReason());
        assertEquals(1, f.host.dispatches, "supervision must retain, not cancel or redispatch");
        f.host.done = true;
        f.resume(run);
        assertEquals(AcxStatus.SUCCESS, f.session.record().status());
        assertEquals(2, f.session.record().completedStepIndex());
        assertEquals(0, f.adapter.pendingCount());
        assertEquals(List.of("after"), f.host.consumed);
    }

    @Test
    void declaredProgressRefreshesBudgetButHeartbeatDoesNot() {
        Fixture f = new Fixture();
        String run = f.start();
        f.clock.set(29_000);
        f.host.sequence = 2;
        f.resume(run);
        assertEquals("body_active", f.session.record().pausedReason());
        f.clock.set(59_000);
        f.host.observedAt = 59_000;
        f.resume(run);
        assertEquals("acx_no_stage_progress", f.session.record().pausedReason());
        f.clock.set(89_000);
        f.resume(run);
        assertEquals("acx_no_stage_progress", f.session.record().pausedReason());
        assertEquals(1, f.host.dispatches);
    }

    @Test void genericRunningProbeUsesTheSameBoundedFactsContract() {
        Fixture f = new Fixture(false);
        String run = f.start();
        f.clock.set(30_000);
        f.resume(run);
        assertEquals("acx_no_stage_progress", f.session.record().pausedReason());
        assertEquals(1, f.host.dispatches);
        assertEquals(1, f.adapter.pendingCount());
    }

    private static final class Fixture {
        final AtomicLong clock = new AtomicLong();
        final Host host;
        final PortToolAdapter adapter;
        final AcxRegistry registry = new AcxRegistry();
        final AcxDefinition definition;
        final AcxRunner runner;
        final AcxSessionManager sessions;
        final AcxFacade facade;
        final AcxRunBinding binding = new AcxRunBinding("no-stage-progress", new Object());
        AcxSessionManager.Session session;

        Fixture() { this(true); }
        Fixture(boolean bodyTagged) {
            host = new Host(bodyTagged);
            adapter = PortToolAdapter.builder(host).probe(host).clock(clock::get).build();
            registry.register(adapter);
            registry.register(new Consumer(host));
            definition = AcxLoader.parseJson(SCRIPT, registry);
            runner = AcxRunner.builder().tools(registry).build();
            sessions = new AcxSessionManager(runner, Runnable::run);
            facade = new AcxFacade(sessions, name -> NAME.equals(name) ? definition : null, registry, null);
        }

        String start() {
            Map<String, Object> accepted = data(facade.executeBound(Map.of("ac_name", NAME), binding));
            session = sessions.session(assertInstanceOf(String.class, accepted.get("run_id")));
            assertNotNull(session);
            assertEquals("async_accepted", session.record().pausedReason());
            return session.runId();
        }

        void resume(String run) {
            Map<String, Object> accepted = data(facade.resumeBound(Map.of("run_id", run), binding));
            assertEquals("RUNNING", accepted.get("state"));
            assertNull(session.error());
        }
    }

    private static final class Host implements AcxToolPort, AcxCompletionProbe {
        final boolean bodyTagged;
        Host(boolean bodyTagged) { this.bodyTagged = bodyTagged; }
        int dispatches;
        int sequence = 1;
        long observedAt;
        boolean done;
        final List<String> consumed = new java.util.ArrayList<>();

        @Override public String name() { return "long_action"; }
        @Override public AcxPortSchema schema() { return new AcxPortSchema(Map.of(), java.util.Set.of(), false); }
        @Override public Result invoke(Map<String, Object> params) { throw new AssertionError("context required"); }
        @Override public Result invoke(Map<String, Object> params, AcxTool.AcxCallContext ctx) {
            dispatches++;
            Result accepted = AcxToolPort.Result.accepted(ACTION.taskId(),
                    Map.of(AcxBodyAction.PROGRESS_FACTS, facts()), "accepted");
            return bodyTagged ? accepted.withBodyAction(ACTION) : accepted;
        }
        @Override public Snapshot probe(String taskId) { throw new AssertionError("context required"); }
        @Override public Snapshot probe(String taskId, AcxTool.AcxCallContext ctx) {
            assertEquals(ACTION.taskId(), taskId);
            return done ? Snapshot.done(Map.of(), "done") : new Snapshot(
                    AcxCompletionProbe.State.RUNNING, Map.of(AcxBodyAction.PROGRESS_FACTS, facts()), "running");
        }
        @Override public AcxBodyAction.Snapshot bodyAction(AcxBodyAction.Ref wanted, AcxTool.AcxCallContext ctx) {
            assertEquals(ACTION, wanted);
            if (done) return new AcxBodyAction.Snapshot(ACTION, AcxBodyAction.State.DONE,
                    AcxBodyAction.Progress.UNKNOWN, AcxBodyAction.Route.UNKNOWN);
            return new AcxBodyAction.Snapshot(ACTION, AcxBodyAction.State.ACTIVE,
                    AcxBodyAction.Progress.VERIFIED, AcxBodyAction.Route.UNKNOWN,
                    Map.of(AcxBodyAction.PROGRESS_FACTS, facts()));
        }

        private Map<String, Object> facts() {
            Map<String, Object> facts = new LinkedHashMap<>();
            facts.put("stage", "navigate");
            facts.put("progress_seq", sequence);
            facts.put("wait_reason", "body_active");
            facts.put("observed_at", observedAt);
            return facts;
        }
    }

    private static final class Consumer implements AcxTool {
        final Host host;
        Consumer(Host host) { this.host = host; }
        @Override public String name() { return "consume_result"; }
        @Override public AcxToolSchema schema() { return AcxToolSchema.builder().build(); }
        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            host.consumed.add("after");
            return AcxStepOutcome.success(Map.of());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        return (Map<String, Object>) assertInstanceOf(Map.class, response.get("data"));
    }
}
