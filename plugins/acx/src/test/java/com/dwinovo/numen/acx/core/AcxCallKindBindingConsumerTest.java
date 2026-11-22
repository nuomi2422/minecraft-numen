package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxCompletionProbe;
import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxLifecycleState;
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
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Real library/catalog/Facade consumers; only host evidence and observing tools are substitutes.
 * Eligibility loss is not body cancellation; these JVM checks do not establish Minecraft effects. */
class AcxCallKindBindingConsumerTest {
    private static final String PARENT = "call_kind_parent";
    private static final String SHARED = "shared_action";
    private static final String PARENT_JSON = """
            {"name":"call_kind_parent","version":"v1","steps":[
              {"id":"call","block":"shared_action"},
              {"id":"consume","block":"consume","params":{"value":"$call.value"}}
            ]}
            """;

    @TempDir Path tempDir;

    @Test
    void publishingSameNamedChildDoesNotRetargetAcceptedAtomicCallOrItsValue() {
        Fixture f = new Fixture(tempDir);
        assertFalse(f.library.hasAnyVersion(SHARED));
        String oldRun = f.start(AcxStatus.PAUSED, 0);
        AcxRunRecord paused = f.state(oldRun, AcxStatus.PAUSED, 0);
        assertTrue(paused.vars().containsValue("tool"), "public record retains an atomic binding marker");
        assertEquals(List.of(new Dispatch("task-1", f.atomic.original)), f.atomic.invoked);
        assertEquals(oldRun, f.atomic.original.runId());
        assertEquals("call", f.atomic.original.stepId());
        assertFalse(f.atomic.original.path().contains("ac:"), "original target is the atomic port, not a child");
        assertTrue(f.atomic.probed.isEmpty(), "acceptance is not completion");
        assertTrue(f.consume.calls.isEmpty());
        assertEquals(1, f.atomicAdapter.pendingCount());

        f.publish("""
                {"name":"shared_action","version":"v2","steps":[
                  {"id":"replacement","block":"replacement_marker","params":{"value":"new"}}
                ]}
                """);
        assertEquals("v2", f.library.activeVersion(SHARED));
        String freshRun = f.start(AcxStatus.SUCCESS, 2);
        assertNotEquals(oldRun, freshRun);
        assertEquals(List.of(new Consumption(freshRun, "replacement", "new")), f.replacement.calls);
        assertEquals(List.of(new Consumption(freshRun, "consume", "new")), f.consume.calls);
        assertEquals(1, f.atomic.invoked.size(), "fresh lookup must choose the newly published child");
        assertTrue(f.atomic.probed.isEmpty(), "fresh run cannot consume the old atomic receipt");
        assertEquals(1, f.atomicAdapter.pendingCount());

        f.atomic.done = true;
        f.resume(oldRun, AcxStatus.SUCCESS, 2);
        assertEquals(List.of(new Dispatch("task-1", f.atomic.original)), f.atomic.probed);
        assertEquals(List.of(new Consumption(freshRun, "consume", "new"),
                new Consumption(oldRun, "consume", "original")), f.consume.calls);
        assertEquals(List.of(new Consumption(freshRun, "replacement", "new")), f.replacement.calls,
                "old resume must not execute the same-named new child");
        assertEquals(1, f.atomic.invoked.size(), "accepted atomic call must not be re-invoked");
        assertTrue(f.child.invoked.isEmpty());
        assertEquals(0, f.atomicAdapter.pendingCount());
        assertEquals(0, f.childAdapter.pendingCount());
        assertEquals("v2", f.library.activeVersion(SHARED));
    }

    @Test
    void rejectingBoundChildFailsBeforeProbeAndCannotFallBackToSameNamedAtomicPort() {
        Fixture f = new Fixture(tempDir);
        f.publish("""
                {"name":"shared_action","version":"v1","steps":[
                  {"id":"act","block":"child_action"}
                ]}
                """);
        String oldRun = f.start(AcxStatus.PAUSED, 0);
        assertTrue(f.registry.contains(SHARED), "a same-named atomic alternative really exists");
        assertTrue(f.atomic.invoked.isEmpty(), "initial lookup must prefer the published child");
        assertEquals(List.of(new Dispatch("task-old", f.child.original)), f.child.invoked);
        assertEquals(oldRun, f.child.original.runId());
        assertEquals("act", f.child.original.stepId());
        assertTrue(f.child.original.path().contains("ac:"), "host acceptance must come from inside the child");
        assertEquals(1, f.childAdapter.pendingCount());

        f.library.reject(SHARED, "v1", "withdraw bound child eligibility");
        assertEquals(AcxLifecycleState.REJECTED, f.library.status(SHARED, "v1"));
        AcxRunRecord failed = f.resume(oldRun, AcxStatus.FAIL, 0);
        assertEquals("call", failed.failedStep());
        assertTrue(failed.errorMessage().contains("已绑定子 AC 定义不可用"), failed.errorMessage());
        assertTrue(failed.errorMessage().contains(SHARED), failed.errorMessage());
        assertTrue(f.atomic.invoked.isEmpty(), "unavailable bound child must not dispatch its atomic namesake");
        assertEquals(1, f.child.invoked.size());
        assertTrue(f.atomic.probed.isEmpty());
        assertTrue(f.child.probed.isEmpty(), "binding eligibility must fail before probing the old task");
        assertTrue(f.consume.calls.isEmpty());
        assertTrue(f.replacement.calls.isEmpty());
        assertEquals(0, f.atomicAdapter.pendingCount());
        assertEquals(1, f.childAdapter.pendingCount(), "rejection does not retire or cancel accepted body work");
        assertFalse(f.sessions.session(oldRun).isCancelRequested());
        assertFalse(f.sessions.session(oldRun).isBodyInvalidated());
    }

    private static final class Fixture {
        final AcxRegistry registry = new AcxRegistry();
        final ControlledHost atomic = new ControlledHost(SHARED, "task-1");
        final ControlledHost child = new ControlledHost("child_action", "task-old");
        final PortToolAdapter atomicAdapter = PortToolAdapter.builder(atomic).probe(atomic).build();
        final PortToolAdapter childAdapter = PortToolAdapter.builder(child).probe(child).build();
        final RecordingTool consume = new RecordingTool("consume");
        final RecordingTool replacement = new RecordingTool("replacement_marker");
        final AcxRunBinding binding = new AcxRunBinding("call-kind-consumer", new Object());
        final FileAcxLibrary library;
        final AcxDefinition parent;
        final AcxRunner runner;
        final AcxSessionManager sessions;
        final AcxFacade facade;

        Fixture(Path directory) {
            registry.register(atomicAdapter);
            registry.register(childAdapter);
            registry.register(consume);
            registry.register(replacement);
            assertTrue(atomicAdapter.schema().outputFields().contains("value"));
            assertTrue(childAdapter.schema().outputFields().contains("value"));
            Path file = directory.resolve("library.json");
            library = new FileAcxLibrary(file, registry);
            library.load();
            parent = publish(PARENT_JSON);
            assertTrue(Files.isRegularFile(file));
            runner = AcxRunner.builder().tools(registry)
                    .catalog(AcxCatalog.withLibrary(library, AcxCatalog.of(Map.of(PARENT, parent)))).build();
            sessions = new AcxSessionManager(runner, Runnable::run);
            facade = new AcxFacade(sessions, library::active, library::version, registry, library);
        }

        AcxDefinition publish(String json) {
            AcxDefinition definition = AcxLoader.parseJson(json, registry);
            assertEquals(definition.version(), library.publish(definition, "call-kind consumer", new HashSet<>(library.names())));
            assertEquals(AcxLifecycleState.STABLE, library.status(definition.name(), definition.version()));
            return definition;
        }

        String start(AcxStatus expected, int completed) {
            Map<String, Object> accepted = data(facade.executeBound(Map.of("ac_name", PARENT), binding));
            assertEquals(PARENT, accepted.get("ac_name"));
            assertEquals("v1", accepted.get("ac_version"));
            assertEquals("RUNNING", accepted.get("status"), "Facade reports acceptance, not synchronous completion");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            String runId = assertInstanceOf(String.class, accepted.get("run_id"));
            state(runId, expected, completed);
            return runId;
        }

        AcxRunRecord resume(String runId, AcxStatus expected, int completed) {
            AcxSessionManager.Session original = sessions.session(runId);
            int checkpoint = original.record().completedStepIndex();
            Map<String, Object> accepted = data(facade.resumeBound(Map.of("run_id", runId), binding));
            assertEquals(runId, accepted.get("run_id"));
            assertEquals("RUNNING", accepted.get("state"), "resume acceptance is separate from the final record");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            assertEquals(checkpoint, accepted.get("resumed_from_step"));
            assertSame(original, sessions.session(runId));
            return state(runId, expected, completed);
        }

        AcxRunRecord state(String runId, AcxStatus expected, int completed) {
            AcxSessionManager.Session session = sessions.session(runId);
            assertTrue(session.matchesBinding(binding));
            assertSame(binding.bodyToken(), session.bodyToken());
            assertFalse(session.isRunning());
            assertFalse(runner.isRunning(runId));
            assertNull(session.error());
            AcxRunRecord record = session.record();
            assertEquals(runId, record.runId());
            assertEquals(PARENT, record.acName());
            assertEquals("v1", record.acVersion());
            assertEquals(AcxFingerprint.of(parent), record.fingerprint());
            assertEquals(expected, record.status(), record.errorMessage());
            assertEquals(completed, record.completedStepIndex());
            Map<String, Object> expectedData = new LinkedHashMap<>(record.toMap());
            expectedData.put("state", expected.name());
            assertEquals(expectedData, data(facade.statusBound(Map.of("run_id", runId), binding)));
            return record;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        return (Map<String, Object>) assertInstanceOf(Map.class, response.get("data"));
    }

    private record Consumption(String runId, String stepId, Object value) { }
    private record Dispatch(String taskId, CallIdentity context) { }
    private record CallIdentity(String runId, String acName, String fingerprint, String path, String stepId, int iteration) {
        static CallIdentity of(AcxCallContext ctx) {
            return new CallIdentity(ctx.runId(), ctx.acName(), ctx.definitionFingerprint(),
                    ctx.invocationPath(), ctx.stepId(), ctx.loopIter());
        }
    }

    private static final class RecordingTool implements AcxTool {
        final String name;
        final List<Consumption> calls = new ArrayList<>();
        RecordingTool(String name) { this.name = name; }
        @Override public String name() { return name; }
        @Override public AcxToolSchema schema() {
            return AcxToolSchema.builder().param("value", AcxToolSchema.ParamType.STRING).required("value").build();
        }
        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            calls.add(new Consumption(ctx.runId(), ctx.stepId(), params.get("value")));
            return AcxStepOutcome.success(Map.of("value", params.get("value")));
        }
    }

    private static final class ControlledHost implements AcxToolPort, AcxCompletionProbe {
        final String name;
        final String taskId;
        final List<Dispatch> invoked = new ArrayList<>();
        final List<Dispatch> probed = new ArrayList<>();
        CallIdentity original;
        boolean done;
        ControlledHost(String name, String taskId) { this.name = name; this.taskId = taskId; }
        @Override public String name() { return name; }
        @Override public AcxPortSchema schema() { return new AcxPortSchema(Map.of(), Set.of("value"), false); }
        @Override public Result invoke(Map<String, Object> params) {
            throw new AssertionError("dispatch requires the real run context");
        }
        @Override public Result invoke(Map<String, Object> params, AcxCallContext ctx) {
            assertTrue(params.isEmpty());
            original = CallIdentity.of(ctx);
            invoked.add(new Dispatch(taskId, original));
            return Result.accepted(taskId, Map.of(), "accepted, not completed");
        }
        @Override public Snapshot probe(String taskId) {
            throw new AssertionError("probe requires the original run context");
        }
        @Override public Snapshot probe(String taskId, AcxCallContext ctx) {
            assertEquals(this.taskId, taskId);
            assertTrue(ctx.isResuming(), "only an explicit resume may probe the old acceptance");
            assertEquals(original, CallIdentity.of(ctx), "task/run/definition/call-site identity must stay original");
            probed.add(new Dispatch(taskId, CallIdentity.of(ctx)));
            return done ? Snapshot.done(Map.of("value", "original"), "original task DONE")
                    : Snapshot.running("original task still running");
        }
    }
}
