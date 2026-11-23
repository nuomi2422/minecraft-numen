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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Real committed-output consumption across explicit same-run resume; no Minecraft effects certified. */
class AcxCommittedOutputResumeConsumerTest {
    private static final String NAME = "committed_output_resume";
    private static final String SCRIPT = """
            {"name":"committed_output_resume","version":"1","steps":[
              {"id":"producer","block":"produce"},
              {"id":"guard","block":"guard","params":{
                "condition":{"field":"$input.ready","op":"==","value":true}}},
              {"id":"consume","block":"consume","params":{"value":"$producer.value"}}
            ]}
            """;

    @Test
    void readyStartCommitsAllThreeStepsAndConsumesActualInteger42Once() {
        Fixture f = new Fixture();
        String run = f.start(true);
        assertAll("normal producer -> production guard -> actual consumer",
                () -> f.state(AcxStatus.SUCCESS, 3, true),
                () -> f.producedOnce(run),
                () -> f.consumed42Once(run));
    }

    @Test
    void explicitSameRunResumeSkipsCommittedProducerButStillDeliversActualInteger42Once() {
        Fixture f = new Fixture();
        String run = f.start(false);
        AcxRunRecord paused = f.state(AcxStatus.PAUSED, 1, false);
        assertEquals("guard", paused.currentStepId());
        assertTrue(paused.pausedReason().contains("guard[guard]"), paused.pausedReason());
        f.producedOnce(run);
        assertTrue(f.consumer.calls.isEmpty(), "the unmet real guard must prevent consumption");
        AcxSessionManager.Session original = f.session;

        // Only the public input changes. Never mutate the definition or replay the producer.
        Map<String, Object> accepted = data(f.facade.resumeBound(
                Map.of("run_id", run, "input", Map.of("ready", true)), f.binding));
        assertEquals(run, accepted.get("run_id"));
        assertEquals("RUNNING", accepted.get("state"), "acceptance is not completion");
        assertEquals(Boolean.TRUE, accepted.get("async"));
        assertEquals(1, accepted.get("resumed_from_step"));
        assertAll("saved output must reach the downstream consumer without redispatch",
                () -> assertSame(original, f.sessions.session(run)),
                () -> assertNotSame(paused, f.session.record()),
                () -> assertEquals(Map.of("ready", false), paused.input()),
                () -> f.state(AcxStatus.SUCCESS, 3, true),
                () -> f.producedOnce(run),
                () -> f.consumed42Once(run));
    }

    @Test
    void importedRootPositionCannotSkipBeyondTheCommittedCheckpoint() {
        Fixture f = new Fixture();
        String run = f.start(false);
        AcxRunRecord paused = f.state(AcxStatus.PAUSED, 1, false);
        Map<String, Object> imported = new LinkedHashMap<>(paused.vars());
        Map<?, ?> root = assertInstanceOf(Map.class, imported.get("_cursor_"));
        Map<String, Object> altered = new LinkedHashMap<>();
        root.forEach((key, value) -> altered.put(String.valueOf(key), value));
        altered.put("step", 2);
        imported.put("_cursor_", altered);
        AcxRunRecord rejected = f.runner.runFrom(f.definition, Map.of("ready", true), 1,
                AcxRuntimeLimits.defaults(), run, imported, true);
        assertEquals(AcxStatus.FAIL, rejected.status(), rejected.errorMessage());
        assertTrue(rejected.errorMessage().contains("断点不一致"), rejected.errorMessage());
        assertEquals(1, rejected.completedStepIndex());
        f.producedOnce(run);
        assertTrue(f.consumer.calls.isEmpty(), "untrusted position cannot authorize a skipped step or consumer");
    }

    @Test
    void explicitNonResumeCannotConsumeASeededTruncatedRootAtAPositiveStartIndex() {
        Fixture f = new Fixture();
        String run = f.start(false);
        AcxRunRecord paused = f.state(AcxStatus.PAUSED, 1, false);
        Map<String, Object> seeded = new LinkedHashMap<>(paused.vars());
        seeded.put("_snapshot_truncated__cursor_", Map.of("total", 65, "kept", 64));
        AcxRunRecord rejected = f.runner.runFrom(f.definition, Map.of("ready", true), 1,
                AcxRuntimeLimits.defaults(), run, seeded, false);
        assertEquals(AcxStatus.FAIL, rejected.status(), rejected.errorMessage());
        assertTrue(rejected.errorMessage().contains("仅可显式恢复"), rejected.errorMessage());
        assertEquals(1, rejected.completedStepIndex());
        f.producedOnce(run);
        assertTrue(f.consumer.calls.isEmpty(), "an explicit non-resume cannot consume any old truncated root");
    }

    @Test
    void legacyPauseWithoutRootSnapshotKeepsExistingNoOutputDependencyResumeBehavior() {
        AcxRegistry tools = new AcxRegistry();
        AcxDefinition def = AcxLoader.parseJson("""
                {"name":"legacy_guard_resume","steps":[
                  {"id":"first","block":"guard","params":{"condition":{"field":true,"op":"==","value":true}}},
                  {"id":"wait","block":"guard","params":{"condition":{"field":"$input.ready","op":"==","value":true}}}
                ]}
                """, tools);
        AcxRunner runner = AcxRunner.builder().tools(tools).build();
        AcxRunRecord paused = runner.run(def, Map.of("ready", false));
        assertEquals(AcxStatus.PAUSED, paused.status());
        assertEquals(1, paused.completedStepIndex());
        Map<String, Object> legacyVars = new LinkedHashMap<>(paused.vars());
        legacyVars.remove("_cursor_");
        AcxRunRecord resumed = runner.runFrom(def, Map.of("ready", true), 1,
                AcxRuntimeLimits.defaults(), paused.runId(), legacyVars, true);
        assertEquals(AcxStatus.SUCCESS, resumed.status(), resumed.errorMessage());
        assertEquals(2, resumed.completedStepIndex());
        // This imports no historical output and does not certify missing legacy outputs as present.
        assertFalse(resumed.vars().containsKey("_cursor_"));
    }

    @Test
    void nestedControlAndCalculationKeepTheirReferencedCommittedOutput() {
        AcxRegistry tools = new AcxRegistry();
        Producer producer = new Producer();
        Consumer consumer = new Consumer();
        tools.register(producer);
        tools.register(consumer);
        AcxDefinition definition = AcxLoader.parseJson("""
                {"name":"nested_output_reference","steps":[
                  {"id":"producer","block":"produce"},
                  {"id":"wait","block":"guard","params":{"condition":{"field":"$input.ready","op":"==","value":true}}},
                  {"id":"branch","block":"if","params":{"condition":{"field":true,"op":"==","value":true}},
                   "children":[{"id":"consume","block":"consume","params":{"value":{"$calc":"$producer.value + 1"}}}]}
                ]}
                """, tools);
        AcxRunner runner = AcxRunner.builder().tools(tools).build();
        AcxSessionManager sessions = new AcxSessionManager(runner, Runnable::run);
        AcxFacade facade = new AcxFacade(sessions, name -> definition, tools, null);
        AcxRunBinding binding = new AcxRunBinding("nested-output-reference", new Object());
        String run = assertInstanceOf(String.class, data(facade.executeBound(
                Map.of("ac_name", definition.name(), "input", Map.of("ready", false)), binding)).get("run_id"));
        assertEquals(AcxStatus.PAUSED, sessions.session(run).record().status());
        assertEquals(1, sessions.session(run).record().completedStepIndex());
        assertTrue(consumer.calls.isEmpty());
        data(facade.resumeBound(Map.of("run_id", run, "input", Map.of("ready", true)), binding));
        AcxRunRecord completed = sessions.session(run).record();
        assertEquals(AcxStatus.SUCCESS, completed.status(), completed.errorMessage());
        assertEquals(3, completed.completedStepIndex());
        assertEquals(1, producer.calls.size(), "a nested reference cannot force redispatch of its producer");
        assertEquals(1, consumer.calls.size());
        assertEquals(run, consumer.calls.get(0).runId());
        assertEquals(43.0, assertInstanceOf(Number.class, consumer.calls.get(0).value()).doubleValue());
    }

    private static final class Fixture {
        final AcxRegistry registry = new AcxRegistry();
        final Producer producer = new Producer();
        final Consumer consumer = new Consumer();
        final AcxRunBinding binding = new AcxRunBinding("committed-output-consumer", new Object());
        final AcxDefinition definition;
        final AcxRunner runner;
        final AcxSessionManager sessions;
        final AcxFacade facade;
        AcxSessionManager.Session session;

        Fixture() {
            registry.register(producer);
            registry.register(consumer);
            assertFalse(registry.contains("guard"), "guard must be Runner's built-in, not a substitute");
            definition = AcxLoader.parseJson(SCRIPT, registry);
            runner = AcxRunner.builder().tools(registry).build();
            sessions = new AcxSessionManager(runner, Runnable::run);
            facade = new AcxFacade(sessions, name -> NAME.equals(name) ? definition : null, registry, null);
        }

        String start(boolean ready) {
            Map<String, Object> accepted = data(facade.executeBound(
                    Map.of("ac_name", NAME, "input", Map.of("ready", ready)), binding));
            assertEquals(NAME, accepted.get("ac_name"));
            assertEquals("1", accepted.get("ac_version"));
            assertEquals("RUNNING", accepted.get("status"), "acceptance is not completion");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            String run = assertInstanceOf(String.class, accepted.get("run_id"));
            session = sessions.session(run);
            assertNotNull(session);
            return run;
        }

        AcxRunRecord state(AcxStatus expected, int checkpoint, boolean ready) {
            String run = session.runId();
            assertSame(session, sessions.session(run));
            assertSame(definition, session.definition());
            assertTrue(session.matchesBinding(binding));
            assertSame(binding.bodyToken(), session.bodyToken());
            assertFalse(session.isRunning());
            assertFalse(runner.isRunning(run));
            assertNull(session.error());
            AcxRunRecord record = session.record();
            assertNotNull(record);
            assertEquals(run, record.runId());
            assertEquals(NAME, record.acName());
            assertEquals("1", record.acVersion());
            assertEquals(AcxFingerprint.of(definition), record.fingerprint());
            assertEquals(expected, record.status(), record.errorMessage());
            assertEquals(checkpoint, record.completedStepIndex());
            assertEquals(Map.of("ready", ready), record.input());
            Map<String, Object> actual = data(facade.statusBound(Map.of("run_id", run), binding));
            Map<String, Object> expectedData = new LinkedHashMap<>(record.toMap());
            expectedData.put("state", expected.name());
            assertEquals(expectedData, actual, "status exposes the complete real record, not acceptance");
            return record;
        }

        void producedOnce(String run) {
            assertEquals(List.of(new Call(run, "producer", 42)), producer.calls);
        }

        void consumed42Once(String run) {
            assertEquals(1, consumer.calls.size(), "actual downstream invocation count");
            Call call = consumer.calls.get(0);
            assertAll("raw consumer argument, without schema rejection or numeric conversion",
                    () -> assertEquals(run, call.runId()),
                    () -> assertEquals("consume", call.stepId()),
                    () -> assertEquals(Integer.class, call.value().getClass()),
                    () -> assertEquals(42, call.value()));
        }
    }

    private record Call(String runId, String stepId, Object value) { }

    private static final class Producer implements AcxTool {
        final List<Call> calls = new ArrayList<>();
        @Override public String name() { return "produce"; }
        @Override public AcxToolSchema schema() {
            // Output fields are declared through params; no producer input is required.
            return new AcxToolSchema(Map.of("value", AcxToolSchema.ParamType.INTEGER), Set.of());
        }
        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            calls.add(new Call(ctx.runId(), ctx.stepId(), 42));
            return AcxStepOutcome.success(Map.of("value", 42), "produced");
        }
    }

    private static final class Consumer implements AcxTool {
        final List<Call> calls = new ArrayList<>();
        @Override public String name() { return "consume"; }
        @Override public AcxToolSchema schema() {
            // ANY lets an unresolved literal reach this observer; assertions enforce actual INTEGER 42.
            return new AcxToolSchema(Map.of("value", AcxToolSchema.ParamType.ANY), Set.of("value"));
        }
        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            Object raw = params.get("value");
            calls.add(new Call(ctx.runId(), ctx.stepId(), raw));
            return AcxStepOutcome.success(Map.of(), "observed");
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        assertInstanceOf(Map.class, response.get("data"));
        return (Map<String, Object>) response.get("data");
    }
}
