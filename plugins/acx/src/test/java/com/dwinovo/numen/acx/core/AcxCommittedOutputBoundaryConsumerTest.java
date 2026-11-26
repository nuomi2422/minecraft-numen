package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxRunBinding;
import com.dwinovo.numen.acx.api.AcxRunRecord;
import com.dwinovo.numen.acx.api.AcxStatus;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import com.dwinovo.numen.acx.api.AcxTool;
import com.dwinovo.numen.acx.api.AcxToolSchema;
import com.dwinovo.numen.plugins.acx.AcxRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/** Pure-computation tool boundaries through real sessions; no Minecraft effects certified. */
class AcxCommittedOutputBoundaryConsumerTest {
    private static final String NAME = "committed_output_boundary";
    private static final String SCRIPT = """
            {"name":"committed_output_boundary","version":"1","steps":[
              {"id":"producer","block":"produce"},
              {"id":"guard","block":"guard","params":{
                "condition":{"field":"$input.ready","op":"==","value":true}}},
              {"id":"consume","block":"consume","params":{"value":"$producer.value"}}
            ]}
            """;

    @Test
    void list64SurvivesSameRunResumeAndFreshExecutionDispatchesIndependently() {
        List<Integer> values = values(64);
        Fixture f = new Fixture(values, false);
        String run = f.start(false);
        AcxRunRecord paused = f.state(run, AcxStatus.PAUSED, 1, false);
        assertEquals("guard", paused.currentStepId());
        assertTrue(paused.pausedReason().contains("guard[guard]"), paused.pausedReason());
        assertFalse(paused.vars().keySet().stream().anyMatch(k -> k.startsWith("_snapshot_truncated_")));
        assertEquals(List.of(new Call(run, "producer", values)), f.producer.calls);
        assertTrue(f.consumer.calls.isEmpty());
        assertAll("same-run output preservation and independent fresh dispatch",
                () -> {
                    f.resume(run, 1);
                    noRootSnapshot(f.state(run, AcxStatus.SUCCESS, 3, true));
                    assertEquals(List.of(new Call(run, "producer", values)), f.producer.calls);
                    f.consumedList(run, 64);
                },
                () -> {
                    String fresh = f.start(true);
                    assertNotEquals(run, fresh);
                    assertNotSame(f.sessions.session(run), f.sessions.session(fresh));
                    noRootSnapshot(f.state(fresh, AcxStatus.SUCCESS, 3, true));
                    assertEquals(List.of(new Call(run, "producer", values),
                            new Call(fresh, "producer", values)), f.producer.calls);
                    f.consumedList(fresh, 64);
                    assertEquals(2, f.consumer.calls.size());
                });
    }

    @Test
    void list65ResumeFailsClosedWithoutReplayButReadyStartConsumesAll65() {
        assertAll("incomplete recovery is not a truncated successful result",
                () -> {
                    List<Integer> values = values(65);
                    Fixture f = new Fixture(values, false);
                    String run = f.start(false);
                    AcxRunRecord paused = f.state(run, AcxStatus.PAUSED, 1, false);
                    assertEquals("guard", paused.currentStepId());
                    assertTrue(paused.vars().keySet().stream()
                            .anyMatch(k -> k.startsWith("_snapshot_truncated__cursor_")));
                    assertEquals(List.of(new Call(run, "producer", values)), f.producer.calls);
                    assertTrue(f.consumer.calls.isEmpty());
                    f.resume(run, 1);
                    AcxRunRecord failed = f.state(run, AcxStatus.FAIL, 1, true);
                    assertTrue(failed.errorMessage().contains("快照不完整"), failed.errorMessage());
                    assertEquals(List.of(new Call(run, "producer", values)), f.producer.calls);
                    assertTrue(f.consumer.calls.isEmpty(), "never consume the saved 64 or replay the producer");
                },
                () -> {
                    Fixture normal = new Fixture(values(65), false);
                    String run = normal.start(true);
                    noRootSnapshot(normal.state(run, AcxStatus.SUCCESS, 3, true));
                    assertEquals(List.of(new Call(run, "producer", values(65))), normal.producer.calls);
                    normal.consumedList(run, 65);
                });
    }

    @Test
    void sessionCancelBeforeCommitPublishesNothingAndExplicitResumeRecomputes42() {
        Fixture f = new Fixture(42, true);
        String run = f.start(true);
        AcxRunRecord paused = f.state(run, AcxStatus.PAUSED, 0, true);
        assertEquals("host_cancelled", paused.pausedReason());
        assertTrue(f.producer.cancelAccepted, "the real session accepted cancellation inside execute");
        assertTrue(f.producer.cancelObserved, "the tool context sees the real runner cancellation");
        noRootSnapshot(paused);
        assertEquals(List.of(new Call(run, "producer", 42)), f.producer.calls);
        assertTrue(f.consumer.calls.isEmpty());
        // This producer has no side effects; only its uncommitted computation may be repeated.
        f.resume(run, 0);
        noRootSnapshot(f.state(run, AcxStatus.SUCCESS, 3, true));
        assertEquals(List.of(new Call(run, "producer", 42), new Call(run, "producer", 42)), f.producer.calls);
        Call consumed = f.onlyConsumption(run);
        assertInstanceOf(Integer.class, consumed.value());
        assertEquals(42, consumed.value());
    }

    @Test
    void fullyConsumedLargeOutputDoesNotBlockAnUnrelatedLaterPauseOrReplayTheLoop() {
        completedLargeOutputAfterLoop(false);
    }

    @Test
    void largeOutputStillReferencedAfterCompletedLoopMustRefuseResumeWithoutReplayingIt() {
        completedLargeOutputAfterLoop(true);
    }

    private static void completedLargeOutputAfterLoop(boolean referencedLater) {
        AcxRegistry registry = new AcxRegistry();
        List<Integer> values = values(65);
        Producer producer = new Producer(values, false, () -> null);
        List<Object> loopValues = new ArrayList<>();
        registry.register(producer);
        registry.register(new AcxTool() {
            @Override public String name() { return "visit"; }
            @Override public AcxToolSchema schema() {
                return new AcxToolSchema(Map.of("value", AcxToolSchema.ParamType.ANY), Set.of("value"));
            }
            @Override public AcxStepOutcome execute(Map<String,Object> params, AcxCallContext ctx) {
                loopValues.add(params.get("value"));
                return AcxStepOutcome.success(Map.of("progress", 1), "one recorded element visited");
            }
        });
        String later = referencedLater ? ", {\"id\":\"read_saved\",\"block\":\"visit\",\"params\":{\"value\":\"$producer.value\"}}" : "";
        AcxDefinition definition = AcxLoader.parseJson("""
                {"name":"consumed_output_scope","steps":[
                  {"id":"producer","block":"produce"},
                  {"id":"all","block":"for","params":{"list":"$producer.value","as":"item"},
                   "children":[{"id":"visit_one","block":"visit","params":{"value":"$var.item"}}]},
                  {"id":"later","block":"guard","params":{"condition":{"field":"$input.ready","op":"==","value":true}}}
                  %s
                ]}
                """.formatted(later), registry);
        AcxRunner runner = AcxRunner.builder().tools(registry).build();
        AcxSessionManager sessions = new AcxSessionManager(runner, Runnable::run);
        AcxFacade facade = new AcxFacade(sessions, name -> definition, registry, null);
        AcxRunBinding binding = new AcxRunBinding("consumed-output-scope", new Object());
        String run = assertInstanceOf(String.class, data(facade.executeBound(
                Map.of("ac_name", definition.name(), "input", Map.of("ready", false)), binding)).get("run_id"));
        AcxRunRecord paused = sessions.session(run).record();
        assertEquals(AcxStatus.PAUSED, paused.status(), paused.errorMessage());
        assertEquals("later", paused.currentStepId(), paused.errorMessage());
        assertTrue(paused.pausedReason().contains("guard[later]"), paused.pausedReason());
        assertEquals(2, paused.completedStepIndex(), paused.errorMessage());
        assertEquals(values, loopValues, "all 65 original elements are processed before the unrelated pause");
        assertEquals(1, producer.calls.size());
        data(facade.resumeBound(Map.of("run_id", run, "input", Map.of("ready", true)), binding));
        AcxRunRecord resumed = sessions.session(run).record();
        assertEquals(referencedLater ? AcxStatus.FAIL : AcxStatus.SUCCESS, resumed.status(), resumed.errorMessage());
        if (referencedLater) assertTrue(resumed.errorMessage().contains("快照不完整"), resumed.errorMessage());
        else assertEquals(3, resumed.completedStepIndex());
        assertEquals(values, loopValues, "neither a completed loop nor an incomplete retained output is replayed");
        assertEquals(1, producer.calls.size());
        assertEquals(resumed.status().name(), data(facade.statusBound(Map.of("run_id", run), binding)).get("state"));
    }

    private static List<Integer> values(int size) { return IntStream.range(0, size).boxed().toList(); }

    private static void noRootSnapshot(AcxRunRecord record) {
        assertFalse(record.vars().containsKey("_cursor_"), "no root output recovery state remains");
        assertFalse(record.vars().keySet().stream()
                .anyMatch(k -> k.startsWith("_snapshot_truncated__cursor_")), "no root truncation marker remains");
    }

    private static final class Fixture {
        final AcxRegistry registry = new AcxRegistry();
        final Consumer consumer = new Consumer();
        final Producer producer;
        final AcxRunBinding binding = new AcxRunBinding("output-boundary-consumer", new Object());
        final AcxDefinition definition;
        final AcxRunner runner;
        final AcxSessionManager sessions;
        final AcxFacade facade;

        Fixture(Object value, boolean cancelFirst) {
            producer = new Producer(value, cancelFirst, this::sessionManager);
            registry.register(producer);
            registry.register(consumer);
            assertFalse(registry.contains("guard"), "use the production built-in guard");
            definition = AcxLoader.parseJson(SCRIPT, registry);
            runner = AcxRunner.builder().tools(registry).build();
            sessions = new AcxSessionManager(runner, Runnable::run);
            facade = new AcxFacade(sessions, name -> NAME.equals(name) ? definition : null, registry, null);
        }
        private AcxSessionManager sessionManager() { return sessions; }
        String start(boolean ready) {
            Map<String, Object> accepted = data(facade.executeBound(
                    Map.of("ac_name", NAME, "input", Map.of("ready", ready)), binding));
            assertEquals("RUNNING", accepted.get("status"), "acceptance is not completion");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            return assertInstanceOf(String.class, accepted.get("run_id"));
        }
        void resume(String run, int checkpoint) {
            AcxSessionManager.Session original = sessions.session(run);
            Map<String, Object> accepted = data(facade.resumeBound(
                    Map.of("run_id", run, "input", Map.of("ready", true)), binding));
            assertEquals(run, accepted.get("run_id"));
            assertEquals("RUNNING", accepted.get("state"), "acceptance is not completion");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            assertEquals(checkpoint, accepted.get("resumed_from_step"));
            assertSame(original, sessions.session(run));
        }
        AcxRunRecord state(String run, AcxStatus status, int checkpoint, boolean ready) {
            AcxSessionManager.Session session = sessions.session(run);
            assertNotNull(session);
            assertSame(definition, session.definition());
            assertTrue(session.matchesBinding(binding));
            assertFalse(session.isRunning());
            assertFalse(runner.isRunning(run));
            assertNull(session.error());
            AcxRunRecord record = session.record();
            assertNotNull(record);
            assertEquals(run, record.runId());
            assertEquals(status, record.status(), record.errorMessage());
            assertEquals(checkpoint, record.completedStepIndex());
            assertEquals(Map.of("ready", ready), record.input());
            assertEquals(status.name(), data(facade.statusBound(Map.of("run_id", run), binding)).get("state"));
            return record;
        }
        Call onlyConsumption(String run) {
            List<Call> calls = consumer.calls.stream().filter(c -> run.equals(c.runId())).toList();
            assertEquals(1, calls.size());
            assertEquals("consume", calls.get(0).stepId());
            return calls.get(0);
        }
        void consumedList(String run, int size) {
            List<?> actual = assertInstanceOf(List.class, onlyConsumption(run).value());
            assertEquals(size, actual.size());
            assertEquals(size - 1, actual.get(size - 1));
            assertEquals(values(size), actual, "raw downstream argument, not the unresolved reference");
        }
    }

    private record Call(String runId, String stepId, Object value) { }
    private static final class Producer implements AcxTool {
        final List<Call> calls = new ArrayList<>();
        final Object value;
        final boolean cancelFirst;
        final Supplier<AcxSessionManager> sessions;
        boolean cancelAccepted, cancelObserved;
        Producer(Object value, boolean cancelFirst, Supplier<AcxSessionManager> sessions) {
            this.value = value; this.cancelFirst = cancelFirst; this.sessions = sessions;
        }
        @Override public String name() { return "produce"; }
        @Override public AcxToolSchema schema() {
            return new AcxToolSchema(Map.of("value", AcxToolSchema.ParamType.ANY), Set.of());
        }
        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            calls.add(new Call(ctx.runId(), ctx.stepId(), value));
            if (cancelFirst && calls.size() == 1) {
                cancelAccepted = sessions.get().cancel(ctx.runId());
                cancelObserved = ctx.isCancelRequested();
            }
            return AcxStepOutcome.success(Map.of("value", value), "computed");
        }
    }
    private static final class Consumer implements AcxTool {
        final List<Call> calls = new ArrayList<>();
        @Override public String name() { return "consume"; }
        @Override public AcxToolSchema schema() {
            return new AcxToolSchema(Map.of("value", AcxToolSchema.ParamType.ANY), Set.of("value"));
        }
        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            calls.add(new Call(ctx.runId(), ctx.stepId(), params.get("value")));
            return AcxStepOutcome.success(Map.of(), "observed");
        }
    }
    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        return (Map<String, Object>) assertInstanceOf(Map.class, response.get("data"));
    }
}
