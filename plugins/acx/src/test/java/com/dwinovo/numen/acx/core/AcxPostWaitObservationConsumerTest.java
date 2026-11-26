package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxCompletionProbe;
import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxPortSchema;
import com.dwinovo.numen.acx.api.AcxRunBinding;
import com.dwinovo.numen.acx.api.AcxRunRecord;
import com.dwinovo.numen.acx.api.AcxStatus;
import com.dwinovo.numen.acx.api.AcxTool.AcxCallContext;
import com.dwinovo.numen.acx.api.AcxToolPort;
import com.dwinovo.numen.plugins.acx.AcxRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Explicit post-wait observation is an AC step, not automatic Runner read replay.
 * Only host reads/actions and completion evidence are substitutes; no MC/world effects are certified.
 */
class AcxPostWaitObservationConsumerTest {
    private static final Map<String, Object> OLD = Map.of("state", "KNOWN", "target", "target-old", "revision", 1);
    private static final Map<String, Object> NEW = Map.of("state", "KNOWN", "target", "target-new", "revision", 2);
    private static final Map<String, Object> UNKNOWN = Map.of("state", "UNKNOWN");
    private static final String JSON = """
            {"name":"post_wait_observation","version":"v1","steps":[
              {"id":"before","block":"read_source","params":{"phase":"before"}},
              {"id":"first","block":"first_action","params":{"target":"$before.target"}},
              {"id":"after","block":"read_source","params":{"phase":"after"}},
              {"id":"known","block":"guard","params":{
                "condition":{"field":"$after.state","op":"==","value":"KNOWN"},
                "hint":"Re-observe and re-plan; do not replay the old target by default"}},
              {"id":"follow","block":"follow_action","params":{
                "target":"$after.target","revision":"$after.revision"}}
            ]}
            """;

    @Test void doneReobservesCurrentSourceAndDispatchesOnlyTheNewTargetOnce() {
        Fixture f = new Fixture(AcxCompletionProbe.State.RUNNING, AcxCompletionProbe.State.DONE);
        String run = f.start();
        AcxSessionManager.Session original = f.sessions.session(run);
        f.current = NEW;

        f.resume(run, AcxStatus.PAUSED, 1);
        f.counts(1, 0, 1);
        assertEquals(List.of(f.probeCall(run, "RUNNING")), f.probes);
        assertEquals(List.of("read:before", "first", "probe:RUNNING"), f.events);

        f.resume(run, AcxStatus.SUCCESS, 5);
        assertSame(original, f.sessions.session(run));
        f.counts(2, 1, 0);
        assertEquals(List.of(Map.of("phase", "before", "value", OLD),
                Map.of("phase", "after", "value", NEW)), f.reads);
        assertEquals(List.of(Map.of("target", "target-new", "revision", 2)), f.follow);
        assertEquals(List.of(f.probeCall(run, "RUNNING"), f.probeCall(run, "DONE")), f.probes);
        assertTrue(f.states.isEmpty());
        assertEquals(List.of("read:before", "first", "probe:RUNNING", "probe:DONE", "read:after", "follow"), f.events);
    }

    @Test void unknownPostWaitObservationPausesGuardWithoutOldOrZeroFallback() {
        Fixture f = new Fixture(AcxCompletionProbe.State.DONE);
        String run = f.start();
        f.current = UNKNOWN; // Missing target/revision are unknown, not zero or the before observation.

        AcxRunRecord stopped = f.resume(run, AcxStatus.PAUSED, 3);
        assertTrue(stopped.pausedReason().contains("guard[known]"), stopped.pausedReason());
        f.counts(2, 0, 0);
        assertEquals(List.of(Map.of("phase", "before", "value", OLD),
                Map.of("phase", "after", "value", UNKNOWN)), f.reads);
        assertFalse(f.current.containsKey("target"));
        assertFalse(f.current.containsKey("revision"));
        assertEquals(List.of(f.probeCall(run, "DONE")), f.probes);
        assertTrue(f.states.isEmpty());
        assertEquals(List.of("read:before", "first", "probe:DONE", "read:after"), f.events);
        // Next: explicitly re-observe/re-plan. Resuming a failed guard is a different recovery question.
    }

    private static final class Fixture implements AcxCompletionProbe {
        Map<String, Object> current = OLD;
        final List<Map<String, Object>> reads = new ArrayList<>(), first = new ArrayList<>(),
                follow = new ArrayList<>(), probes = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        final ArrayDeque<State> states = new ArrayDeque<>();
        List<Object> originalContext;
        final AcxRegistry registry = new AcxRegistry();
        final List<PortToolAdapter> adapters = new ArrayList<>();
        final AcxRunBinding binding = new AcxRunBinding("post-wait-consumer", new Object());
        final AcxDefinition definition;
        final AcxRunner runner;
        final AcxSessionManager sessions;
        final AcxFacade facade;

        Fixture(State... script) {
            states.addAll(List.of(script));
            register("read_source", Map.of("phase", stringParam().withEnum("before", "after")),
                    Set.of("state", "target", "revision"), (params, ctx) -> {
                        Map<String, Object> value = Map.copyOf(current);
                        reads.add(Map.of("phase", params.get("phase"), "value", value));
                        events.add("read:" + params.get("phase"));
                        return AcxToolPort.Result.completed(value);
                    });
            register("first_action", Map.of("target", stringParam()), Set.of(), (params, ctx) -> {
                first.add(Map.copyOf(params));
                originalContext = identity(ctx);
                events.add("first");
                return AcxToolPort.Result.accepted("task-1", Map.of(), "accepted, not completed");
            });
            register("follow_action", Map.of("target", stringParam(),
                    "revision", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER)), Set.of(), (params, ctx) -> {
                        follow.add(Map.copyOf(params));
                        events.add("follow");
                        return AcxToolPort.Result.completed(Map.of(), "fixture receipt, not world-effect proof");
                    });
            definition = AcxLoader.parseJson(JSON, registry);
            runner = AcxRunner.builder().tools(registry).build();
            sessions = new AcxSessionManager(runner, Runnable::run);
            facade = new AcxFacade(sessions,
                    name -> definition.name().equals(name) ? definition : null, registry, null);
        }

        void register(String name, Map<String, AcxPortSchema.Param> inputs, Set<String> outputs,
                      BiFunction<Map<String, Object>, AcxCallContext, AcxToolPort.Result> call) {
            AcxToolPort port = new AcxToolPort() {
                public String name() { return name; }
                public AcxPortSchema schema() { return new AcxPortSchema(inputs, outputs, false); }
                public Result invoke(Map<String, Object> params) {
                    throw new AssertionError("host calls require the real Runner context");
                }
                public Result invoke(Map<String, Object> params, AcxCallContext ctx) {
                    assertNotNull(ctx);
                    return call.apply(params, ctx);
                }
            };
            PortToolAdapter adapter = PortToolAdapter.builder(port).probe(this).build();
            adapters.add(adapter);
            registry.register(adapter);
        }

        String start() {
            Map<String, Object> accepted = data(facade.executeBound(Map.of("ac_name", definition.name()), binding));
            assertEquals("RUNNING", accepted.get("status"), "acceptance is not the execution outcome");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            String run = (String) accepted.get("run_id");
            assertNotNull(run);
            AcxRunRecord record = status(run, AcxStatus.PAUSED, 1);
            assertEquals("async_accepted", record.pausedReason());
            counts(1, 0, 1);
            assertEquals(List.of(Map.of("phase", "before", "value", OLD)), reads);
            assertEquals(run, originalContext.get(0));
            assertTrue(probes.isEmpty(), "execute must not probe or read after acceptance");
            return run;
        }

        AcxRunRecord resume(String run, AcxStatus expected, int checkpoint) {
            AcxSessionManager.Session original = sessions.session(run);
            int from = original.record().completedStepIndex();
            Map<String, Object> accepted = data(facade.resumeBound(Map.of("run_id", run), binding));
            assertEquals(run, accepted.get("run_id"));
            assertEquals("RUNNING", accepted.get("state"), "resume acceptance is not final status");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            assertEquals(from, accepted.get("resumed_from_step"));
            assertSame(original, sessions.session(run));
            return status(run, expected, checkpoint);
        }

        AcxRunRecord status(String run, AcxStatus expected, int checkpoint) {
            AcxSessionManager.Session session = sessions.session(run);
            assertTrue(session.matchesBinding(binding));
            assertFalse(session.isRunning());
            assertFalse(runner.isRunning(run));
            assertNull(session.error());
            AcxRunRecord record = session.record();
            assertEquals(run, record.runId());
            assertEquals(expected, record.status(), record.errorMessage());
            assertEquals(checkpoint, record.completedStepIndex());
            Map<String, Object> expectedData = new LinkedHashMap<>(record.toMap());
            expectedData.put("state", expected.name());
            assertEquals(expectedData, data(facade.statusBound(Map.of("run_id", run), binding)));
            return record;
        }

        void counts(int readCount, int followCount, int pending) {
            assertEquals(readCount, reads.size());
            assertEquals(List.of(Map.of("target", "target-old")), first, "first dispatch is never replayed");
            assertEquals(followCount, follow.size());
            assertEquals(pending, adapters.stream().mapToInt(PortToolAdapter::pendingCount).sum());
        }

        Map<String, Object> probeCall(String run, String state) {
            return Map.of("run_id", run, "task_id", "task-1", "state", state);
        }

        @Override public Snapshot probe(String taskId) {
            throw new AssertionError("probe requires the original Runner context");
        }

        @Override public Snapshot probe(String taskId, AcxCallContext ctx) {
            assertEquals("task-1", taskId);
            assertTrue(ctx.isResuming());
            assertEquals(originalContext, identity(ctx), "probe must preserve original run and structural call");
            State state = states.removeFirst();
            probes.add(probeCall(ctx.runId(), state.name()));
            events.add("probe:" + state.name());
            return state == State.DONE ? Snapshot.done(Map.of(), "original task completed")
                    : Snapshot.running("original task still running");
        }
    }

    private static AcxPortSchema.Param stringParam() {
        return AcxPortSchema.Param.req(AcxPortSchema.Type.STRING);
    }

    private static List<Object> identity(AcxCallContext ctx) {
        return List.of(ctx.runId(), ctx.acName(), ctx.definitionFingerprint(), ctx.invocationPath(), ctx.stepId(), ctx.loopIter());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        return (Map<String, Object>) assertInstanceOf(Map.class, response.get("data"));
    }
}
