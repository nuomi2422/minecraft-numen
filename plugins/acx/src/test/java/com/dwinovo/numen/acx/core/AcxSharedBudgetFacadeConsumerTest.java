package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxEvent;
import com.dwinovo.numen.acx.api.AcxRunRecord;
import com.dwinovo.numen.acx.api.AcxStatus;
import com.dwinovo.numen.acx.api.AcxStepOutcome;
import com.dwinovo.numen.acx.api.AcxTool;
import com.dwinovo.numen.acx.api.AcxToolSchema;
import com.dwinovo.numen.plugins.acx.AcxRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real library -> facade -> direct session executor -> runner budget consumption.
 * Only the leaf marker is a tool substitute; it records calls, not Minecraft effects.
 * Counts cover one attempt and fresh runs only, not a cross-resume budget contract.
 */
class AcxSharedBudgetFacadeConsumerTest {
    private static final String PARENT = "shared_budget_parent";
    private static final String CHILD = "shared_budget_child";
    private static final String CHILD_JSON = """
            {"name":"shared_budget_child","version":"v1",
             "limits":{"max_steps":10000,"max_depth":100},"steps":[
              {"id":"leaf1","block":"leaf_marker","params":{"call":"$input.call","ordinal":1}},
              {"id":"leaf2","block":"leaf_marker","params":{"call":"$input.call","ordinal":2}},
              {"id":"leaf3","block":"leaf_marker","params":{"call":"$input.call","ordinal":3}}
            ]}
            """;
    private static final String PARENT_JSON = """
            {"name":"shared_budget_parent","version":"v1",
             "limits":{"max_steps":%d,"max_depth":32},"steps":[
              {"id":"first","block":"shared_budget_child","params":{"call":"first"}},
              {"id":"second","block":"shared_budget_child","params":{"call":"second"}}
            ]}
            """;

    @TempDir Path tempDir;

    @Test
    void eightStepsExactlyCompleteBothCallSitesAndFreshRunGetsIndependentBudget() {
        Fixture f = new Fixture(tempDir, 8);
        // Each child call itself costs one step, plus its three leaves: 4 + 4 = 8.
        AcxRunRecord first = f.execute(AcxStatus.SUCCESS, 2);
        f.assertLeaves(first.runId(), 6);
        assertEquals(6, f.marker.calls.size());

        AcxRunRecord fresh = f.execute(AcxStatus.SUCCESS, 2);
        assertNotEquals(first.runId(), fresh.runId());
        assertEquals(first.fingerprint(), fresh.fingerprint(), "same published parent, not a new definition");
        f.assertLeaves(first.runId(), 6);
        f.assertLeaves(fresh.runId(), 6);
        assertEquals(12, f.marker.calls.size(), "fresh must really dispatch six more leaves");
        assertTrue(f.circuits().isEmpty(), "exactly reaching eight after the last leaf is not a failure");
    }

    @Test
    void singleAtomicStepAtTheLimitSucceedsWithoutAPostExecutionStepFailure() {
        var f = new InlineFixture("""
                {"name":"single_budget","version":"v1","limits":{"max_steps":1},"steps":[
                  {"id":"only","block":"leaf_marker","params":{"call":"once","ordinal":1}}
                ]}
                """);
        AcxRunRecord record = f.execute(Map.of());
        assertEquals(AcxStatus.SUCCESS, record.status(), record.errorMessage());
        assertEquals(1, record.completedStepIndex());
        assertEquals(1, f.marker.calls.size());
        assertTrue(f.circuits().isEmpty());
    }

    @Test
    void forLoopAtTheLimitSucceedsWhenNoFurtherChildStarts() {
        var f = new InlineFixture("""
                {"name":"for_budget","version":"v1","limits":{"max_steps":3},"steps":[
                  {"id":"loop","block":"for","params":{"list":"$input.items","as":"item"},"children":[
                    {"id":"leaf","block":"leaf_marker","params":{"call":"$var.item","ordinal":1}}
                  ]}
                ]}
                """);
        AcxRunRecord record = f.execute(Map.of("items", List.of("a", "b", "c")));
        assertEquals(AcxStatus.SUCCESS, record.status(), record.errorMessage());
        assertEquals(1, record.completedStepIndex());
        assertEquals(3, f.marker.calls.size());
        assertTrue(f.circuits().isEmpty());
    }

    @Test
    void forLoopOverTheLimitStillStopsBeforeTheExtraChild() {
        var f = new InlineFixture("""
                {"name":"for_budget_over","version":"v1","limits":{"max_steps":2},"steps":[
                  {"id":"loop","block":"for","params":{"list":"$input.items","as":"item"},"children":[
                    {"id":"leaf","block":"leaf_marker","params":{"call":"$var.item","ordinal":1}}
                  ]}
                ]}
                """);
        AcxRunRecord record = f.execute(Map.of("items", List.of("a", "b", "c")));
        assertEquals(AcxStatus.FAIL, record.status(), record.errorMessage());
        assertTrue(record.errorMessage().contains("最大总步数 2"), record.errorMessage());
        assertEquals(2, f.marker.calls.size());
        assertEquals(1, f.circuits().size());
    }

    @Test
    void sevenStepsStopBeforeSecondCallSitesLastLeafDespiteLargeChildLimits() {
        Fixture f = new Fixture(tempDir, 7);
        AcxRunRecord failed = f.execute(AcxStatus.FAIL, 1);
        assertEquals("second", failed.currentStepId());
        assertEquals("second", failed.failedStep());
        assertTrue(failed.errorMessage().contains("最大总步数 7"), failed.errorMessage());
        f.assertLeaves(failed.runId(), 5);
        assertEquals(5, f.marker.calls.size(), "second/leaf3 must never be invoked");

        List<AcxEvent> circuits = f.circuits();
        assertEquals(1, circuits.size());
        AcxEvent circuit = circuits.get(0);
        assertEquals(failed.runId(), circuit.runId());
        assertEquals(PARENT, circuit.acName());
        assertEquals("second", circuit.stepId());
        assertEquals(AcxStatus.FAIL, circuit.status());
        assertEquals(Map.of("max_steps", 7), circuit.detail(), "child max_steps=10000 must not reset parent budget");
    }

    private static final class Fixture {
        final LeafMarker marker = new LeafMarker();
        final List<AcxEvent> events = new ArrayList<>();
        final AcxDefinition parent;
        final AcxRunner runner;
        final AcxSessionManager sessions;
        final AcxFacade facade;

        Fixture(Path directory, int maxSteps) {
            AcxRegistry registry = new AcxRegistry();
            registry.register(marker);
            Path libraryFile = directory.resolve("library.json");
            FileAcxLibrary library = new FileAcxLibrary(libraryFile, registry);
            library.load();
            AcxDefinition child = AcxLoader.parseJson(CHILD_JSON, registry);
            assertEquals("v1", library.publish(child, "three observable leaves", new HashSet<>(library.names())));
            assertEquals(10000, child.limits().maxSteps().intValue());
            // Loader allows child names outside the tool registry; publish verifies the real known AC names.
            parent = AcxLoader.parseJson(PARENT_JSON.formatted(maxSteps), registry);
            assertFalse(registry.contains(CHILD));
            assertEquals("v1", library.publish(parent, "two calls share one attempt budget", new HashSet<>(library.names())));
            assertTrue(Files.isRegularFile(libraryFile), "real publish must persist in the JUnit temporary directory");
            AcxCatalog catalog = AcxCatalog.withLibrary(library, AcxCatalog.empty());
            runner = AcxRunner.builder().tools(registry).catalog(catalog).events(events::add).build();
            sessions = new AcxSessionManager(runner, Runnable::run);
            facade = new AcxFacade(sessions, library::active, library::version, registry, library);
        }

        AcxRunRecord execute(AcxStatus expected, int completed) {
            Map<String, Object> accepted = data(facade.execute(Map.of("ac_name", PARENT)));
            assertEquals("RUNNING", accepted.get("status"), "execute reports acceptance, not the terminal record");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            assertEquals(PARENT, accepted.get("ac_name"));
            assertEquals("v1", accepted.get("ac_version"));
            String runId = (String) accepted.get("run_id");
            assertNotNull(runId);
            AcxSessionManager.Session session = sessions.session(runId);
            assertNotNull(session);
            assertFalse(session.isRunning(), "direct executor has already returned from the attempt");
            assertFalse(runner.isRunning(runId));
            assertNull(session.error());
            AcxRunRecord record = session.record();
            assertNotNull(record);
            assertEquals(runId, record.runId());
            assertEquals(PARENT, record.acName());
            assertEquals("v1", record.acVersion());
            assertEquals(AcxFingerprint.of(parent), record.fingerprint());
            assertEquals(expected, record.status(), record.errorMessage());
            assertEquals(completed, record.completedStepIndex());
            Map<String, Object> expectedData = new LinkedHashMap<>(record.toMap());
            expectedData.put("state", expected.name());
            assertEquals(expectedData, data(facade.status(Map.of("run_id", runId))),
                    "successful status query must expose the real SUCCESS/FAIL record");
            return record;
        }

        void assertLeaves(String runId, int count) {
            List<LeafCall> calls = marker.calls.stream().filter(call -> runId.equals(call.runId())).toList();
            assertEquals(count, calls.size());
            for (int i = 0; i < count; i++) {
                LeafCall call = calls.get(i);
                int ordinal = i % 3 + 1;
                assertEquals("leaf" + ordinal, call.stepId(), "actual leaf step context, in dispatch order");
                assertEquals(java.util.Set.of("call", "ordinal"), call.params().keySet());
                assertEquals(i < 3 ? "first" : "second", call.params().get("call"));
                assertInstanceOf(Number.class, call.params().get("ordinal"));
                assertEquals(ordinal, ((Number) call.params().get("ordinal")).intValue());
            }
        }

        List<AcxEvent> circuits() {
            return events.stream().filter(event -> event.kind() == AcxEvent.Kind.CIRCUIT_STEPS).toList();
        }
    }

    private static final class InlineFixture {
        final LeafMarker marker = new LeafMarker();
        final List<AcxEvent> events = new ArrayList<>();
        final AcxDefinition definition;
        final AcxSessionManager sessions;
        final AcxRegistry registry;

        InlineFixture(String json) {
            registry = new AcxRegistry();
            registry.register(marker);
            definition = AcxLoader.parseJson(json, registry);
            AcxRunner runner = AcxRunner.builder().tools(registry).events(events::add).build();
            sessions = new AcxSessionManager(runner, Runnable::run);
        }

        AcxRunRecord execute(Map<String, Object> input) {
            AcxFacade facade = new AcxFacade(sessions,
                    name -> definition.name().equals(name) ? definition : null, registry, null);
            Map<String, Object> accepted = data(facade.execute(Map.of("ac_name", definition.name(), "input", input)));
            String runId = assertInstanceOf(String.class, accepted.get("run_id"));
            AcxRunRecord record = sessions.session(runId).record();
            assertNotNull(record);
            assertFalse(sessions.session(runId).isRunning());
            return record;
        }

        List<AcxEvent> circuits() {
            return events.stream().filter(event -> event.kind() == AcxEvent.Kind.CIRCUIT_STEPS).toList();
        }
    }

    private record LeafCall(String runId, String stepId, Map<String, Object> params) { }

    private static final class LeafMarker implements AcxTool {
        final List<LeafCall> calls = new ArrayList<>();

        @Override public String name() { return "leaf_marker"; }

        @Override public AcxToolSchema schema() {
            return AcxToolSchema.builder()
                    .param("call", AcxToolSchema.ParamType.STRING).required("call")
                    .param("ordinal", AcxToolSchema.ParamType.INTEGER).required("ordinal").build();
        }

        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            calls.add(new LeafCall(ctx.runId(), ctx.stepId(), Map.copyOf(params)));
            return AcxStepOutcome.success(Map.of("progress", 1));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        assertInstanceOf(Map.class, response.get("data"));
        return (Map<String, Object>) response.get("data");
    }
}
