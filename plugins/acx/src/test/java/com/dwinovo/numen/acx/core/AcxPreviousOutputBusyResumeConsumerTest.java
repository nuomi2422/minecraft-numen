package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.*;
import com.dwinovo.numen.plugins.acx.AcxRegistry;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

/** BODY_BUSY proves non-admission; this fixture does not certify a physical body becoming free. */
class AcxPreviousOutputBusyResumeConsumerTest {
    private static final String NAME = "previous_output_busy";
    private static final String SCRIPT = """
            {"name":"previous_output_busy","version":"1","steps":[
              {"id":"producer","block":"produce"},
              {"id":"use_previous","block":"consume_previous","params":{"value":"$prev.value"}}
            ]}
            """;

    @Test void normalCallConsumesTheProducersPreviousIntegerWithoutResume() {
        Fixture f = new Fixture(false);
        String run = f.start();
        f.state(run, AcxStatus.SUCCESS, 2);
        f.assertCompleted(run, List.of(false));
    }

    @Test void confirmedNonAdmissionCanRetryWithSavedPreviousValueWithoutReplayingProducer() {
        Fixture f = new Fixture(true);
        String run = f.start();
        AcxSessionManager.Session session = f.sessions.session(run);
        AcxRunRecord paused = f.state(run, AcxStatus.PAUSED, 1);
        assertEquals("body_busy", paused.pausedReason());
        assertEquals("use_previous", paused.currentStepId());
        assertEquals(1, f.producerRuns.size());
        assertEquals(List.of(42), f.host.values);
        assertEquals(0, f.host.admissions, "BODY_BUSY is not task acceptance");
        assertEquals(0, f.adapter.pendingCount(), "there is no accepted task receipt to infer completion from");
        assertEquals(0, f.host.probes);
        Map<String,Object> accepted = data(f.facade.resumeBound(Map.of("run_id", run), f.binding));
        assertEquals("RUNNING", accepted.get("state"), "resume is only acceptance of the attempt");
        assertEquals(1, accepted.get("resumed_from_step"));
        assertSame(session, f.sessions.session(run));
        f.state(run, AcxStatus.SUCCESS, 2);
        assertEquals(List.of(42, 42), f.host.values, "retry must receive the original raw Integer, never a missing reference");
        assertEquals(List.of(false, true), f.host.resumeModes);
        assertEquals(1, f.host.admissions);
        assertEquals(List.of(run), f.producerRuns);
        assertEquals(0, f.adapter.pendingCount());
        assertEquals(0, f.host.probes, "no task id was accepted, so no completion probe is justified");
    }

    private static final class Fixture {
        final List<String> producerRuns = new ArrayList<>();
        final Host host;
        final PortToolAdapter adapter;
        final AcxRunBinding binding = new AcxRunBinding("previous-output-busy", new Object());
        final AcxDefinition definition;
        final AcxSessionManager sessions;
        final AcxFacade facade;
        Fixture(boolean busyFirst) {
            AcxRegistry registry = new AcxRegistry();
            registry.register(new AcxTool() {
                @Override public String name() { return "produce"; }
                @Override public AcxToolSchema schema() {
                    return new AcxToolSchema(Map.of("value", AcxToolSchema.ParamType.INTEGER), Set.of());
                }
                @Override public AcxStepOutcome execute(Map<String,Object> params, AcxCallContext ctx) {
                    producerRuns.add(ctx.runId());
                    return AcxStepOutcome.success(Map.of("value", 42));
                }
            });
            host = new Host(busyFirst);
            adapter = PortToolAdapter.builder(host).probe(host).build();
            registry.register(adapter);
            definition = AcxLoader.parseJson(SCRIPT, registry);
            AcxRunner runner = AcxRunner.builder().tools(registry).build();
            sessions = new AcxSessionManager(runner, Runnable::run);
            facade = new AcxFacade(sessions, name -> definition, registry, null);
        }
        String start() {
            Map<String,Object> accepted = data(facade.executeBound(Map.of("ac_name", NAME), binding));
            assertEquals("RUNNING", accepted.get("status"));
            assertEquals(Boolean.TRUE, accepted.get("async"));
            return assertInstanceOf(String.class, accepted.get("run_id"));
        }
        AcxRunRecord state(String run, AcxStatus status, int checkpoint) {
            AcxSessionManager.Session session = sessions.session(run);
            assertNotNull(session);
            assertTrue(session.matchesBinding(binding));
            assertFalse(session.isRunning());
            assertNull(session.error());
            AcxRunRecord record = session.record();
            assertEquals(status, record.status(), record.errorMessage());
            assertEquals(checkpoint, record.completedStepIndex());
            Map<String,Object> expected = new LinkedHashMap<>(record.toMap());
            expected.put("state", status.name());
            assertEquals(expected, data(facade.statusBound(Map.of("run_id", run), binding)));
            return record;
        }
        void assertCompleted(String run, List<Boolean> modes) {
            assertEquals(List.of(run), producerRuns);
            assertEquals(List.of(42), host.values);
            assertEquals(modes, host.resumeModes);
            assertEquals(1, host.admissions);
            assertEquals(0, host.probes);
            assertEquals(0, adapter.pendingCount());
        }
    }

    private static final class Host implements AcxToolPort, AcxCompletionProbe {
        final boolean busyFirst;
        final List<Object> values = new ArrayList<>();
        final List<Boolean> resumeModes = new ArrayList<>();
        int admissions, probes;
        Host(boolean busyFirst) { this.busyFirst = busyFirst; }
        @Override public String name() { return "consume_previous"; }
        @Override public AcxPortSchema schema() {
            return new AcxPortSchema(Map.of("value", AcxPortSchema.Param.req(AcxPortSchema.Type.INTEGER)), Set.of(), false);
        }
        @Override public Result invoke(Map<String,Object> params) { throw new AssertionError("run context required"); }
        @Override public Result invoke(Map<String,Object> params, AcxTool.AcxCallContext ctx) {
            assertInstanceOf(Integer.class, params.get("value"));
            values.add(params.get("value"));
            resumeModes.add(ctx.isResuming());
            if (busyFirst && values.size() == 1) return Result.bodyBusy("another-task", "original body not available");
            admissions++;
            return Result.completed(Map.of("observed", true));
        }
        @Override public Snapshot probe(String taskId) { probes++; throw new AssertionError("unaccepted call cannot be probed"); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> data(Map<String,Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        return (Map<String,Object>) assertInstanceOf(Map.class, response.get("data"));
    }
}
