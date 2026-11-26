package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.*;
import com.dwinovo.numen.acx.api.AcxTool.AcxCallContext;
import com.dwinovo.numen.plugins.acx.AcxRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real library/catalog/facade consumers; only host evidence is substituted, not body safety/effects. */
class AcxTopLevelVersionResumeConsumerTest {
    private static final String NAME = "top_version_parent";
    @TempDir Path tempDir;

    @Test
    void newActiveVersionServesFreshRunWhileOldRunConsumesItsOriginalTask() {
        Fixture f = new Fixture(tempDir);
        String oldRun = f.start(f.original);
        AcxSessionManager.Session oldSession = f.sessions.session(oldRun);
        AcxRunRecord paused = oldSession.record();
        Dispatch oldCall = f.host.dispatched.get(0);
        assertEquals("task-v1", oldCall.taskId());
        assertTrue(f.host.probed.isEmpty(), "acceptance is not completion");
        assertTrue(f.consumer.consumed.isEmpty());
        assertEquals(1, f.adapter.pendingCount());

        AcxDefinition v2 = f.publish("v2", "replacement");
        assertEquals("v2", f.library.activeVersion(NAME));
        assertEquals(AcxLifecycleState.STABLE, f.library.status(NAME, "v1"));
        assertSame(f.original, f.library.version(NAME, "v1"));
        assertNotEquals(AcxFingerprint.of(f.original), AcxFingerprint.of(v2));
        String freshRun = f.start(v2);
        Dispatch freshCall = f.host.dispatched.get(1);
        assertNotEquals(oldRun, freshRun);
        assertEquals("task-v2", freshCall.taskId());
        assertNotEquals(oldCall.identity().fingerprint(), freshCall.identity().fingerprint());
        assertEquals(2, f.adapter.pendingCount(), "fresh acceptance must not consume the old receipt");

        f.host.doneSet.add("task-v2");
        accepted(f.facade.resumeBound(Map.of("run_id", freshRun), f.binding), freshRun);
        AcxRunRecord freshDone = f.state(freshRun, v2, AcxStatus.SUCCESS, 2);
        assertEquals(List.of(new Probe(freshCall, AcxCompletionProbe.State.DONE)), f.host.probed);
        assertEquals(List.of(new Consumption(freshRun, "v2")), f.consumer.consumed);
        assertSame(paused, f.state(oldRun, f.original, AcxStatus.PAUSED, 0));
        assertEquals(1, f.adapter.pendingCount(), "only the old task remains pending");

        f.host.doneSet.add("task-v1");
        // Keep the raw refusal visible: active v2 is not same-v1 content drift (019 L54/L108).
        Map<String, Object> resumeMap = f.facade.resumeBound(Map.of("run_id", oldRun), f.binding);
        assertAll("old run must resolve original stable v1, not compare it against active v2: " + resumeMap,
                () -> accepted(resumeMap, oldRun),
                () -> {
                    if (Boolean.TRUE.equals(resumeMap.get("success"))) {
                        AcxRunRecord oldDone = f.state(oldRun, f.original, AcxStatus.SUCCESS, 2);
                        assertNotSame(freshDone, oldDone);
                        assertNotEquals(freshDone.fingerprint(), oldDone.fingerprint());
                    }
                },
                () -> assertSame(oldSession, f.sessions.session(oldRun)),
                () -> assertEquals(List.of(oldCall, freshCall), f.host.dispatched, "resume must not invoke again"),
                () -> assertEquals(List.of(new Probe(freshCall, AcxCompletionProbe.State.DONE),
                        new Probe(oldCall, AcxCompletionProbe.State.DONE)), f.host.probed,
                        "refusing v1 at the facade prevents the original task/fingerprint probe"),
                () -> assertEquals(List.of(new Consumption(freshRun, "v2"), new Consumption(oldRun, "v1")),
                        f.consumer.consumed, "each run must consume its own actual data.value exactly once"),
                () -> assertEquals(0, f.adapter.pendingCount(), "accepted old completion must commit its receipt"),
                () -> assertEquals("v2", f.library.activeVersion(NAME), "resume must not roll active back"));
    }

    @Test
    void sameVersionContentReplacementStillRefusesResumeWithoutRetiringAcceptedAction() {
        Fixture f = new Fixture(tempDir);
        String run = f.start(f.original);
        AcxSessionManager.Session session = f.sessions.session(run);
        AcxRunRecord paused = session.record();
        String fingerprint = AcxFingerprint.of(f.original);
        Dispatch originalCall = f.host.dispatched.get(0);
        AcxDefinition replacement = f.publish("v1", "changed-payload");
        assertNotSame(f.original, replacement);
        assertSame(replacement, f.library.version(NAME, "v1"));
        assertNotEquals(fingerprint, AcxFingerprint.of(replacement));
        assertEquals(fingerprint, AcxFingerprint.of(f.original), "real publish must not mutate the old object");
        assertEquals("v1", f.library.activeVersion(NAME));
        assertEquals(AcxLifecycleState.STABLE, f.library.status(NAME, "v1"));

        Map<String, Object> resumeMap = f.facade.resumeBound(Map.of("run_id", run), f.binding);
        assertAll("AC-B22 same-version content guard: " + resumeMap,
                () -> assertEquals(Boolean.FALSE, resumeMap.get("success")),
                () -> assertTrue(String.valueOf(resumeMap.get("message")).contains("内容已变"), resumeMap.toString()),
                () -> assertSame(session, f.sessions.session(run)),
                () -> assertSame(paused, f.state(run, f.original, AcxStatus.PAUSED, 0)),
                () -> assertEquals(List.of(originalCall), f.host.dispatched),
                () -> assertEquals(List.of(), f.host.probed, "definition refusal must precede host probing"),
                () -> assertEquals(List.of(), f.consumer.consumed, "no completion result was consumed"),
                () -> assertEquals(1, f.adapter.pendingCount(), "definition refusal is not action cancellation"),
                () -> assertFalse(session.isBodyInvalidated()),
                () -> assertFalse(session.isCancelRequested()));
    }

    @Test
    void rejectedOriginalVersionCannotResumeThroughAnUnrelatedActiveVersionOrStartupCopy() {
        Fixture f = new Fixture(tempDir);
        String run = f.start(f.original);
        AcxSessionManager.Session session = f.sessions.session(run);
        AcxRunRecord paused = session.record();
        f.publish("v2", "new-active");
        f.library.reject(NAME, "v1", "withdraw original runtime eligibility");
        assertEquals("v2", f.library.activeVersion(NAME));
        assertEquals(AcxLifecycleState.REJECTED, f.library.status(NAME, "v1"));
        Map<String,Object> refused = f.facade.resumeBound(Map.of("run_id", run), f.binding);
        assertAll("a stable active replacement cannot authorize a rejected original run",
                () -> assertEquals(Boolean.FALSE, refused.get("success"), refused.toString()),
                () -> assertSame(session, f.sessions.session(run)),
                () -> assertSame(paused, f.state(run, f.original, AcxStatus.PAUSED, 0)),
                () -> assertEquals(1, f.host.dispatched.size()),
                () -> assertTrue(f.host.probed.isEmpty()),
                () -> assertTrue(f.consumer.consumed.isEmpty()),
                () -> assertEquals(1, f.adapter.pendingCount(), "eligibility refusal is not task cancellation"),
                () -> assertEquals("v2", f.library.activeVersion(NAME)));
    }

    @Test
    void missingOrGeneratedOriginalVersionCannotFallBackToItsCapturedDefinition() throws Exception {
        for (String mode : List.of("missing", "generated")) {
            Path root = tempDir.resolve(mode);
            Fixture f = new Fixture(root);
            String run = f.start(f.original);
            AcxSessionManager.Session session = f.sessions.session(run);
            AcxRunRecord paused = session.record();
            f.publish("v2", "new-active");
            if ("missing".equals(mode)) {
                Path file = root.resolve("library.json");
                var saved = com.google.gson.JsonParser.parseString(Files.readString(file)).getAsJsonObject();
                saved.getAsJsonObject("defs").getAsJsonObject(NAME).getAsJsonObject("versions").remove("v1");
                Files.writeString(file, saved.toString());
                f.library.load();
                assertThrows(IllegalArgumentException.class, () -> f.library.version(NAME, "v1"));
            } else {
                f.library.reject(NAME, "v1", "withdraw");
                f.library.reopen(NAME, "v1");
                assertEquals(AcxLifecycleState.GENERATED, f.library.status(NAME, "v1"));
            }
            assertTrue(f.library.hasAnyVersion(NAME));
            assertEquals("v2", f.library.activeVersion(NAME));
            Map<String,Object> refused = f.facade.resumeBound(Map.of("run_id", run), f.binding);
            assertAll(mode + " must refuse before original task probing",
                    () -> assertEquals(Boolean.FALSE, refused.get("success"), refused.toString()),
                    () -> assertSame(session, f.sessions.session(run)),
                    () -> assertSame(paused, session.record()),
                    () -> assertEquals(AcxStatus.PAUSED, session.record().status()),
                    () -> assertEquals(1, f.host.dispatched.size()),
                    () -> assertTrue(f.host.probed.isEmpty()),
                    () -> assertTrue(f.consumer.consumed.isEmpty()),
                    () -> assertEquals(1, f.adapter.pendingCount()),
                    () -> assertEquals("v2", f.library.activeVersion(NAME)));
        }
    }

    @Test
    void genuinelyInlineRunWithoutPublishedNameStillConsumesItsOriginalReceipt() {
        for (boolean attachEmptyLibrary : List.of(false, true)) {
            AcxRegistry registry = new AcxRegistry();
            ControlledHost host = new ControlledHost();
            RecordingConsumer consumer = new RecordingConsumer();
            PortToolAdapter adapter = PortToolAdapter.builder(host).probe(host).build();
            registry.register(adapter);
            registry.register(consumer);
            FileAcxLibrary library = attachEmptyLibrary
                    ? new FileAcxLibrary(tempDir.resolve("inline-library.json"), registry) : null;
            if (library != null) { library.load(); assertFalse(library.hasAnyVersion(NAME)); }
            AcxRunner runner = AcxRunner.builder().tools(registry).build();
            AcxSessionManager sessions = new AcxSessionManager(runner, Runnable::run);
            AcxFacade facade = new AcxFacade(sessions,
                    name -> library == null ? null : library.active(name),
                    (name, version) -> library == null ? null : library.version(name, version), registry, library);
            AcxRunBinding binding = new AcxRunBinding("inline-version-consumer", new Object());
            String script = """
                    {"name":"top_version_parent","version":"v1","steps":[
                      {"id":"action","block":"action_async","params":{"version":"v1","marker":"inline"}},
                      {"id":"consume","block":"consume","params":{"value":"$action.value"}}
                    ]}
                    """;
            Map<String,Object> started = data(facade.executeBound(Map.of("ac_json", script), binding));
            assertEquals("RUNNING", started.get("status"));
            String run = assertInstanceOf(String.class, started.get("run_id"));
            AcxSessionManager.Session session = sessions.session(run);
            assertEquals(AcxStatus.PAUSED, session.record().status());
            assertEquals(0, session.record().completedStepIndex());
            assertEquals(1, adapter.pendingCount());
            Dispatch original = host.dispatched.get(0);
            host.doneSet.add(original.taskId());
            accepted(facade.resumeBound(Map.of("run_id", run), binding), run);
            assertSame(session, sessions.session(run));
            assertEquals(AcxStatus.SUCCESS, session.record().status(), session.record().errorMessage());
            assertEquals(2, session.record().completedStepIndex());
            assertEquals(List.of(original), host.dispatched);
            assertEquals(List.of(new Probe(original, AcxCompletionProbe.State.DONE)), host.probed);
            assertEquals(List.of(new Consumption(run, "v1")), consumer.consumed);
            assertEquals(0, adapter.pendingCount());
        }
    }

    private static final class Fixture {
        final AcxRegistry registry = new AcxRegistry();
        final ControlledHost host = new ControlledHost();
        final RecordingConsumer consumer = new RecordingConsumer();
        final PortToolAdapter adapter = PortToolAdapter.builder(host).probe(host).build();
        final AcxRunBinding binding = new AcxRunBinding("top-version-consumer", new Object());
        final FileAcxLibrary library;
        final AcxDefinition original;
        final AcxRunner runner;
        final AcxSessionManager sessions;
        final AcxFacade facade;

        Fixture(Path directory) {
            registry.register(adapter);
            registry.register(consumer);
            Path file = directory.resolve("library.json");
            library = new FileAcxLibrary(file, registry);
            library.load();
            original = publish("v1", "initial");
            assertTrue(Files.isRegularFile(file));
            AcxCatalog startup = AcxCatalog.of(Map.of(NAME, original));
            runner = AcxRunner.builder().tools(registry).catalog(AcxCatalog.withLibrary(library, startup)).build();
            sessions = new AcxSessionManager(runner, Runnable::run);
            facade = new AcxFacade(sessions, library::active, library::version, registry, library);
        }

        AcxDefinition publish(String version, String marker) {
            AcxDefinition definition = AcxLoader.parseJson("""
                    {"name":"top_version_parent","version":"%s","steps":[
                      {"id":"action","block":"action_async","params":{"version":"%s","marker":"%s"}},
                      {"id":"consume","block":"consume","params":{"value":"$action.value"}}
                    ]}
                    """.formatted(version, version, marker), registry);
            assertEquals(version, library.publish(definition, "top-level consumer " + marker, new HashSet<>(library.names())));
            return definition;
        }

        String start(AcxDefinition definition) {
            Map<String, Object> response = data(facade.executeBound(Map.of("ac_name", NAME), binding));
            assertEquals(NAME, response.get("ac_name"));
            assertEquals(definition.version(), response.get("ac_version"));
            assertEquals("RUNNING", response.get("status"), "acceptance is not final status");
            assertEquals(Boolean.TRUE, response.get("async"));
            String run = assertInstanceOf(String.class, response.get("run_id"));
            state(run, definition, AcxStatus.PAUSED, 0);
            Dispatch call = host.dispatched.get(host.dispatched.size() - 1);
            assertEquals(run, call.identity().runId());
            assertEquals(AcxFingerprint.of(definition), call.identity().fingerprint());
            assertEquals(definition.version(), call.version());
            return run;
        }

        AcxRunRecord state(String run, AcxDefinition definition, AcxStatus expected, int checkpoint) {
            AcxSessionManager.Session session = sessions.session(run);
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
            assertEquals(definition.version(), record.acVersion());
            assertEquals(AcxFingerprint.of(definition), record.fingerprint());
            assertEquals(expected, record.status(), record.errorMessage());
            assertEquals(checkpoint, record.completedStepIndex());
            Map<String, Object> actualStatus = data(facade.statusBound(Map.of("run_id", run), binding));
            Map<String, Object> completeRecord = new LinkedHashMap<>(record.toMap());
            completeRecord.put("state", expected.name());
            assertEquals(completeRecord, actualStatus, "status must expose the complete actual record");
            return record;
        }
    }

    private static void accepted(Map<String, Object> response, String run) {
        Map<String, Object> accepted = data(response);
        assertEquals(run, accepted.get("run_id"));
        assertEquals("RUNNING", accepted.get("state"), "resume acceptance is not the resulting status");
        assertEquals(Boolean.TRUE, accepted.get("async"));
        assertEquals(0, accepted.get("resumed_from_step"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> response) {
        assertEquals(Boolean.TRUE, response.get("success"), response.toString());
        return (Map<String, Object>) assertInstanceOf(Map.class, response.get("data"));
    }

    private record Identity(String runId, String acName, String fingerprint, String path, String stepId, int iteration) {
        static Identity of(AcxCallContext ctx) {
            return new Identity(ctx.runId(), ctx.acName(), ctx.definitionFingerprint(), ctx.invocationPath(), ctx.stepId(), ctx.loopIter());
        }
    }
    private record Dispatch(String taskId, Identity identity, String version) { }
    private record Probe(Dispatch original, AcxCompletionProbe.State state) { }
    private record Consumption(String runId, Object rawObject) { }

    private static final class RecordingConsumer implements AcxTool {
        final List<Consumption> consumed = new ArrayList<>();
        @Override public String name() { return "consume"; }
        @Override public AcxToolSchema schema() {
            return AcxToolSchema.builder().param("value", AcxToolSchema.ParamType.STRING).required("value").build();
        }
        @Override public AcxStepOutcome execute(Map<String, Object> params, AcxCallContext ctx) {
            Object rawObject = params.get("value");
            assertInstanceOf(String.class, rawObject);
            consumed.add(new Consumption(ctx.runId(), rawObject));
            return AcxStepOutcome.success(Map.of("value", rawObject));
        }
    }

    private static final class ControlledHost implements AcxToolPort, AcxCompletionProbe {
        final List<Dispatch> dispatched = new ArrayList<>();
        final List<Probe> probed = new ArrayList<>();
        final Set<String> doneSet = new HashSet<>();
        @Override public String name() { return "action_async"; }
        @Override public AcxPortSchema schema() {
            return new AcxPortSchema(Map.of("version", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING),
                    "marker", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING)), Set.of("value"), false);
        }
        @Override public Result invoke(Map<String, Object> params) { throw new AssertionError("run context required"); }
        @Override public Result invoke(Map<String, Object> params, AcxCallContext ctx) {
            assertNotNull(ctx);
            assertEquals(NAME, ctx.acName());
            assertEquals("action", ctx.stepId());
            assertFalse(ctx.invocationPath().isBlank());
            String version = (String) params.get("version");
            String task = "task-" + version;
            assertTrue(dispatched.stream().noneMatch(call -> call.taskId().equals(task)), "must not re-dispatch accepted task");
            dispatched.add(new Dispatch(task, Identity.of(ctx), version));
            return Result.accepted(task, Map.of(), "accepted, not completed");
        }
        @Override public Snapshot probe(String taskId) { throw new AssertionError("original run context required"); }
        @Override public Snapshot probe(String taskId, AcxCallContext ctx) {
            assertTrue(ctx.isResuming());
            Dispatch original = dispatched.stream().filter(call -> call.taskId().equals(taskId)).findFirst().orElseThrow();
            assertEquals(original.identity(), Identity.of(ctx), "task/run/definitionFingerprint/path must all match");
            State state = doneSet.contains(taskId) ? State.DONE : State.RUNNING;
            probed.add(new Probe(original, state));
            return state == State.DONE ? Snapshot.done(Map.of("value", original.version()), "original result")
                    : Snapshot.running("original task pending");
        }
    }
}
