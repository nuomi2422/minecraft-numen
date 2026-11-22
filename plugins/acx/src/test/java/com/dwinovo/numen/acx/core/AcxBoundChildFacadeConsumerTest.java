package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxCompletionProbe;
import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxLifecycleState;
import com.dwinovo.numen.acx.api.AcxPortSchema;
import com.dwinovo.numen.acx.api.AcxRunBinding;
import com.dwinovo.numen.acx.api.AcxRunRecord;
import com.dwinovo.numen.acx.api.AcxStatus;
import com.dwinovo.numen.acx.api.AcxTool.AcxCallContext;
import com.dwinovo.numen.acx.api.AcxToolPort;
import com.dwinovo.numen.plugins.acx.AcxRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Consumer coverage for the production library/catalog/facade binding chain.
 * Only host acceptance and completion evidence are substituted; all attempts run synchronously.
 * Losing a definition's eligibility is not proof that an accepted body action was cancelled.
 * These JVM checks do not establish Minecraft effects or body stop/release.
 */
class AcxBoundChildFacadeConsumerTest {
    private static final String PARENT = "bound_child_parent";
    private static final String CHILD = "bound_child";
    private static final String PARENT_JSON = """
            {"name":"bound_child_parent","version":"v1","steps":[
              {"id":"first","block":"bound_child","params":{"call":"first"}},
              {"id":"second","block":"bound_child","params":{"call":"second"}}
            ]}
            """;

    @TempDir Path tempDir;

    @Test
    void publishingV2KeepsBothOldCallSitesOnV1AndIsolatesFreshReceipts() {
        Fixture f = new Fixture(tempDir);
        String oldRun = f.start();
        AcxSessionManager.Session oldSession = f.sessions.session(oldRun);
        assertEquals(List.of(new Dispatch("task-1", oldRun, "v1", "initial", "first")), f.host.dispatched);
        assertTrue(f.host.probed.isEmpty(), "acceptance is not completion");
        assertEquals(1, f.adapter.pendingCount());

        f.publishChild("v2", "replacement");
        assertEquals("v2", f.library.activeVersion(CHILD));
        assertEquals(AcxLifecycleState.STABLE, f.library.status(CHILD, "v1"));
        f.resume(oldRun, AcxStatus.PAUSED, 0);
        assertEquals(1, f.host.dispatched.size(), "still-running v1 task must not be re-dispatched");
        assertEquals(List.of(new Probe("task-1", oldRun, "v1", AcxCompletionProbe.State.RUNNING)), f.host.probed);

        String freshRun = f.start();
        assertNotEquals(oldRun, freshRun);
        assertEquals(2, f.adapter.pendingCount(), "fresh v2 must not consume the old run's receipt");
        assertEquals(new Dispatch("task-2", freshRun, "v2", "replacement", "first"), f.host.dispatched.get(1));

        f.host.complete("task-1");
        f.resume(oldRun, AcxStatus.PAUSED, 1);
        assertSame(oldSession, f.sessions.session(oldRun));
        assertEquals(new Dispatch("task-3", oldRun, "v1", "initial", "second"), f.host.dispatched.get(2),
                "the run-wide child binding must survive the first parent checkpoint");
        assertEquals(2, f.adapter.pendingCount(), "first v1 receipt retired; second v1 and fresh v2 remain");

        f.resume(freshRun, AcxStatus.PAUSED, 0);
        assertEquals(3, f.host.dispatched.size(), "old task completion cannot finish or re-dispatch fresh task-2");
        f.host.complete("task-2");
        f.resume(freshRun, AcxStatus.PAUSED, 1);
        assertEquals(2, f.adapter.pendingCount());
        f.host.complete("task-3");
        f.resume(oldRun, AcxStatus.SUCCESS, 2);
        assertEquals(1, f.adapter.pendingCount());
        f.host.complete("task-4");
        f.resume(freshRun, AcxStatus.SUCCESS, 2);

        assertEquals(List.of(
                new Dispatch("task-1", oldRun, "v1", "initial", "first"),
                new Dispatch("task-2", freshRun, "v2", "replacement", "first"),
                new Dispatch("task-3", oldRun, "v1", "initial", "second"),
                new Dispatch("task-4", freshRun, "v2", "replacement", "second")), f.host.dispatched);
        assertEquals(List.of(
                new Probe("task-1", oldRun, "v1", AcxCompletionProbe.State.RUNNING),
                new Probe("task-1", oldRun, "v1", AcxCompletionProbe.State.DONE),
                new Probe("task-2", freshRun, "v2", AcxCompletionProbe.State.RUNNING),
                new Probe("task-2", freshRun, "v2", AcxCompletionProbe.State.DONE),
                new Probe("task-3", oldRun, "v1", AcxCompletionProbe.State.DONE),
                new Probe("task-4", freshRun, "v2", AcxCompletionProbe.State.DONE)), f.host.probed);
        assertEquals(0, f.adapter.pendingCount(), "all four exact completions have committed");
        assertEquals("v2", f.library.activeVersion(CHILD), "old resume must not roll back active");
    }

    @Test
    void rejectingBoundV1FailsOldResumeBeforeProbeWithoutDowngradingActiveV2() {
        Fixture f = new Fixture(tempDir);
        String oldRun = f.start();
        f.publishChild("v2", "replacement");
        f.library.reject(CHILD, "v1", "withdraw old child eligibility");
        assertEquals(AcxLifecycleState.REJECTED, f.library.status(CHILD, "v1"));
        assertEquals("v2", f.library.activeVersion(CHILD));
        assertTrue(f.library.isOnline(CHILD));

        AcxRunRecord failed = f.resume(oldRun, AcxStatus.FAIL, 0);
        assertTrue(failed.errorMessage().contains("已绑定子 AC 定义不可用"), failed.errorMessage());
        assertTrue(failed.errorMessage().contains(CHILD), failed.errorMessage());
        assertEquals("first", failed.failedStep());
        assertEquals(List.of(new Dispatch("task-1", oldRun, "v1", "initial", "first")), f.host.dispatched);
        assertTrue(f.host.probed.isEmpty(), "reject guard must run before touching the accepted old task");
        assertEquals(1, f.adapter.pendingCount());
        assertFalse(f.sessions.session(oldRun).isBodyInvalidated(), "library rejection is not body invalidation");
        assertFalse(f.sessions.session(oldRun).isCancelRequested(), "eligibility loss does not request body cancellation");

        String freshRun = f.start();
        assertNotEquals(oldRun, freshRun);
        assertEquals(new Dispatch("task-2", freshRun, "v2", "replacement", "first"), f.host.dispatched.get(1));
        assertEquals(2, f.adapter.pendingCount());
        f.host.complete("task-2");
        f.resume(freshRun, AcxStatus.PAUSED, 1);
        f.host.complete("task-3");
        f.resume(freshRun, AcxStatus.SUCCESS, 2);

        assertEquals(List.of(
                new Dispatch("task-1", oldRun, "v1", "initial", "first"),
                new Dispatch("task-2", freshRun, "v2", "replacement", "first"),
                new Dispatch("task-3", freshRun, "v2", "replacement", "second")), f.host.dispatched);
        assertEquals(List.of(
                new Probe("task-2", freshRun, "v2", AcxCompletionProbe.State.DONE),
                new Probe("task-3", freshRun, "v2", AcxCompletionProbe.State.DONE)), f.host.probed);
        f.status(oldRun, AcxStatus.FAIL, 0);
        assertEquals("v2", f.library.activeVersion(CHILD));
        assertEquals(AcxLifecycleState.STABLE, f.library.status(CHILD, "v2"));
        assertEquals(1, f.adapter.pendingCount(),
                "only old unprobed acceptance remains; definition rejection is not receipt retirement or body stop");
    }

    @Test
    void republishingV1WithDifferentContentCannotRetargetTheOldRun() {
        Fixture f = new Fixture(tempDir);
        String oldRun = f.start();
        String oldChildFingerprint = AcxFingerprint.of(f.library.version(CHILD, "v1"));
        AcxDefinition replacement = f.publishChild("v1", "rewritten");
        assertEquals("v1", f.library.activeVersion(CHILD));
        assertNotEquals(oldChildFingerprint, AcxFingerprint.of(replacement), "same version must still change content identity");

        AcxRunRecord failed = f.resume(oldRun, AcxStatus.FAIL, 0);
        assertTrue(failed.errorMessage().contains("已绑定子 AC 定义不可用"), failed.errorMessage());
        assertEquals("first", failed.failedStep());
        assertEquals(List.of(new Dispatch("task-1", oldRun, "v1", "initial", "first")), f.host.dispatched);
        assertTrue(f.host.probed.isEmpty(), "neither rewritten v1 nor stale startup v1 can satisfy the binding");
        assertEquals(1, f.adapter.pendingCount());

        String freshRun = f.start();
        assertNotEquals(oldRun, freshRun);
        assertEquals(new Dispatch("task-2", freshRun, "v1", "rewritten", "first"), f.host.dispatched.get(1));
        assertEquals(2, f.adapter.pendingCount());
        f.host.complete("task-2");
        f.resume(freshRun, AcxStatus.PAUSED, 1);
        f.host.complete("task-3");
        f.resume(freshRun, AcxStatus.SUCCESS, 2);

        assertEquals(List.of(
                new Dispatch("task-1", oldRun, "v1", "initial", "first"),
                new Dispatch("task-2", freshRun, "v1", "rewritten", "first"),
                new Dispatch("task-3", freshRun, "v1", "rewritten", "second")), f.host.dispatched);
        assertEquals(List.of(
                new Probe("task-2", freshRun, "v1", AcxCompletionProbe.State.DONE),
                new Probe("task-3", freshRun, "v1", AcxCompletionProbe.State.DONE)), f.host.probed);
        f.status(oldRun, AcxStatus.FAIL, 0);
        assertEquals(AcxFingerprint.of(replacement), AcxFingerprint.of(f.library.active(CHILD)));
        assertEquals(1, f.adapter.pendingCount(), "fresh completion must not consume the old content's acceptance");
    }

    private static String childJson(String version, String payload) {
        return """
                {"name":"bound_child","version":"%s","steps":[
                  {"id":"act","block":"async_action",
                   "params":{"version":"%s","payload":"%s","call":"$input.call"}}
                ]}
                """.formatted(version, version, payload);
    }

    private static final class Fixture {
        final AcxRegistry registry = new AcxRegistry();
        final ControlledHost host = new ControlledHost();
        final PortToolAdapter adapter = PortToolAdapter.builder(host).probe(host).build();
        final AcxRunBinding binding = new AcxRunBinding("bound-child-consumer", new Object());
        final FileAcxLibrary library;
        final AcxDefinition parent;
        final AcxRunner runner;
        final AcxSessionManager sessions;
        final AcxFacade facade;

        Fixture(Path directory) {
            registry.register(adapter);
            Path libraryFile = directory.resolve("library.json");
            library = new FileAcxLibrary(libraryFile, registry);
            library.load();
            AcxDefinition originalChild = publishChild("v1", "initial");
            parent = AcxLoader.parseJson(PARENT_JSON, registry);
            assertEquals("v1", library.publish(parent, "two child call sites", new HashSet<>(library.names())));
            assertTrue(Files.isRegularFile(libraryFile), "publish must use the real temporary file-backed library");
            // Real parsed startup entries deliberately stay stale after library publish/reject.
            AcxCatalog startup = AcxCatalog.of(Map.of(PARENT, parent, CHILD, originalChild));
            runner = AcxRunner.builder().tools(registry).catalog(AcxCatalog.withLibrary(library, startup)).build();
            sessions = new AcxSessionManager(runner, Runnable::run);
            facade = new AcxFacade(sessions, library::active, library::version, registry, library);
        }

        AcxDefinition publishChild(String version, String payload) {
            AcxDefinition child = AcxLoader.parseJson(childJson(version, payload), registry);
            assertEquals(version, library.publish(child, "consumer fixture " + payload, new HashSet<>(library.names())));
            assertEquals(AcxLifecycleState.STABLE, library.status(CHILD, version));
            return child;
        }

        String start() {
            Map<String, Object> accepted = data(facade.executeBound(Map.of("ac_name", PARENT), binding));
            assertEquals(PARENT, accepted.get("ac_name"));
            assertEquals("v1", accepted.get("ac_version"));
            assertEquals("RUNNING", accepted.get("status"), "execute reports acceptance, not synchronous completion");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            String runId = (String) accepted.get("run_id");
            assertNotNull(runId);
            status(runId, AcxStatus.PAUSED, 0);
            return runId;
        }

        AcxRunRecord resume(String runId, AcxStatus expected, int completed) {
            AcxSessionManager.Session session = sessions.session(runId);
            int resumedFrom = session.record().completedStepIndex();
            Map<String, Object> accepted = data(facade.resumeBound(Map.of("run_id", runId), binding));
            assertEquals(runId, accepted.get("run_id"));
            assertEquals("RUNNING", accepted.get("state"), "resume acceptance is separate from the resulting status");
            assertEquals(Boolean.TRUE, accepted.get("async"));
            assertEquals(resumedFrom, accepted.get("resumed_from_step"));
            assertSame(session, sessions.session(runId), "resume must preserve the session and run identity");
            return status(runId, expected, completed);
        }

        AcxRunRecord status(String runId, AcxStatus expected, int completed) {
            AcxSessionManager.Session session = sessions.session(runId);
            assertNotNull(session);
            assertTrue(session.matchesBinding(binding));
            assertSame(binding.bodyToken(), session.bodyToken());
            assertFalse(session.isRunning(), "direct executor has returned from this attempt");
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
            Map<String, Object> actual = data(facade.statusBound(Map.of("run_id", runId), binding));
            Map<String, Object> expectedData = new LinkedHashMap<>(record.toMap());
            expectedData.put("state", expected.name());
            assertEquals(expectedData, actual, "facade must expose the real session record, including failure/paused details");
            return record;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        assertInstanceOf(Map.class, response.get("data"));
        return (Map<String, Object>) response.get("data");
    }

    private record Dispatch(String taskId, String runId, String version, String payload, String call) { }
    private record Probe(String taskId, String runId, String version, AcxCompletionProbe.State state) { }
    private record CallIdentity(String runId, String acName, String fingerprint, String path, String stepId, int iteration) {
        static CallIdentity of(AcxCallContext ctx) {
            return new CallIdentity(ctx.runId(), ctx.acName(), ctx.definitionFingerprint(),
                    ctx.invocationPath(), ctx.stepId(), ctx.loopIter());
        }
    }

    /** Host-only substitutes: task-id evidence must be queried in the original structural/run context. */
    private static final class ControlledHost implements AcxToolPort, AcxCompletionProbe {
        final List<Dispatch> dispatched = new ArrayList<>();
        final List<Probe> probed = new ArrayList<>();
        final Map<String, CallIdentity> originalContexts = new HashMap<>();
        final Set<String> completed = new HashSet<>();

        @Override public String name() { return "async_action"; }

        @Override public AcxPortSchema schema() {
            return new AcxPortSchema(Map.of(
                    "version", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING),
                    "payload", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING),
                    "call", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING)),
                    Set.of("version", "payload", "call"), false);
        }

        @Override public Result invoke(Map<String, Object> params) {
            throw new AssertionError("host dispatch must receive the original run context");
        }

        @Override public Result invoke(Map<String, Object> params, AcxCallContext ctx) {
            assertNotNull(ctx);
            String taskId = "task-" + (dispatched.size() + 1);
            dispatched.add(new Dispatch(taskId, ctx.runId(), (String) params.get("version"),
                    (String) params.get("payload"), (String) params.get("call")));
            originalContexts.put(taskId, CallIdentity.of(ctx));
            return Result.accepted(taskId, params, "host accepted this exact async action");
        }

        void complete(String taskId) {
            assertTrue(originalContexts.containsKey(taskId), "cannot complete an undispatched task");
            completed.add(taskId);
        }

        @Override public Snapshot probe(String taskId) {
            throw new AssertionError("completion probe must receive the original run context");
        }

        @Override public Snapshot probe(String taskId, AcxCallContext ctx) {
            assertNotNull(ctx);
            assertTrue(ctx.isResuming(), "only explicit resume may probe acceptance");
            assertEquals(originalContexts.get(taskId), CallIdentity.of(ctx), "probe must preserve task/run/definition/call-site identity");
            Dispatch original = dispatched.stream().filter(call -> call.taskId().equals(taskId)).findFirst().orElseThrow();
            AcxCompletionProbe.State state = completed.contains(taskId)
                    ? AcxCompletionProbe.State.DONE : AcxCompletionProbe.State.RUNNING;
            probed.add(new Probe(taskId, ctx.runId(), original.version(), state));
            return state == AcxCompletionProbe.State.DONE
                    ? Snapshot.done(Map.of("version", original.version(), "payload", original.payload(), "call", original.call()),
                            "exact original task completed")
                    : Snapshot.running("exact original task still running");
        }
    }
}
