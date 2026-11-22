package com.dwinovo.numen.acx.core;

import com.dwinovo.numen.acx.api.AcxCompletionProbe;
import com.dwinovo.numen.acx.api.AcxDefinition;
import com.dwinovo.numen.acx.api.AcxPortSchema;
import com.dwinovo.numen.acx.api.AcxRunRecord;
import com.dwinovo.numen.acx.api.AcxStatus;
import com.dwinovo.numen.acx.api.AcxTool.AcxCallContext;
import com.dwinovo.numen.acx.api.AcxToolPort;
import com.dwinovo.numen.plugins.acx.AcxRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Receipt retirement through the real loader, runner, session manager and adapter.
 * Only the host port/probe boundary is controlled; execution stays on the calling thread.
 * A real 65-item for snapshot makes resume fail before it can resolve the accepted receipt.
 * Retiring that inactive terminal run must forget only its receipts, never a resumable pause.
 * These are JVM lifecycle checks, not evidence of body stop/release or Minecraft effects.
 */
class AcxTerminalReceiptLifecycleTest {
    private static final String LOOP_JSON = """
            {"name":"terminal_receipt_lifecycle","version":"1","steps":[
              {"id":"each","block":"for","params":{"list":"$input.items","as":"m"},
               "children":[{"id":"act","block":"async_action","params":{"item":"$var.m"}}]}
            ]}
            """;
    private static final String SET_JSON = """
            {"name":"terminal_receipt_set_only","version":"1","steps":[
              {"id":"done","block":"set","params":{"done":true}}
            ]}
            """;

    @Test
    void explicitPurgeRetiresTheFailedRunAndItsAcceptedReceipt() {
        Fixture f = new Fixture(32);
        String oldRun = f.failAfterRealTruncatedSnapshot();
        assertFalse(f.runner.isRunning(oldRun), "runner must have exited before retirement");

        assertEquals(1, f.sessions.purgeFinished());

        assertAll("purge permanently retires the old run, not just its session entry",
                () -> assertNull(f.sessions.session(oldRun)),
                () -> assertFalse(f.sessions.resume(oldRun, null)),
                () -> assertEquals(0, f.adapter.pendingCount(),
                        "purged terminal run must not leak its accepted receipt"),
                () -> assertEquals(1, f.host.dispatched.size()),
                () -> assertTrue(f.host.probed.isEmpty()));
    }

    @Test
    void automaticEvictionDropsOnlyTheOldFailedRunsReceipt() {
        Fixture f = new Fixture(1);
        String oldRun = f.failAfterRealTruncatedSnapshot();
        String freshRun = f.startPaused(1);
        AcxSessionManager.Session freshSession = f.sessions.session(freshRun);
        assertNotEquals(oldRun, freshRun);
        assertNotNull(f.sessions.session(oldRun), "one terminal run still fits maxFinished=1");
        assertEquals(2, f.adapter.pendingCount(), "old and fresh accepted receipts coexist");
        assertEquals(2, f.host.dispatched.size());
        assertTrue(f.host.probed.isEmpty());
        assertEquals(new Dispatch("task-2", freshRun, "item-0"), f.host.dispatched.get(1));

        String triggerRun = f.sessions.start(f.setOnly, Map.of());
        f.record(triggerRun, AcxStatus.SUCCESS);
        assertFalse(f.runner.isRunning(oldRun));

        // Keep checking the fresh run even when the old-receipt cleanup assertion is red.
        assertAll("maxFinished eviction isolates receipts by the retired run id",
                () -> assertNull(f.sessions.session(oldRun)),
                () -> assertFalse(f.sessions.resume(oldRun, null)),
                () -> assertSame(freshSession, f.sessions.session(freshRun)),
                () -> f.record(freshRun, AcxStatus.PAUSED),
                () -> assertEquals(1, f.adapter.pendingCount(),
                        "only the still-resumable fresh receipt may remain"),
                () -> {
                    assertEquals(2, f.host.dispatched.size(), "set-only run must not dispatch");
                    assertTrue(f.host.probed.isEmpty(), "eviction must not probe either task");
                    f.host.completed.add("task-2");
                    assertTrue(f.sessions.resume(freshRun, null));
                    // Its successful finish may evict this older inserted session at maxFinished=1.
                    // Inspect the original session, not a retention guarantee the manager never made.
                    assertFalse(freshSession.isRunning());
                    assertNull(freshSession.error());
                    AcxRunRecord done = freshSession.record();
                    assertNotNull(done);
                    assertEquals(AcxStatus.SUCCESS, done.status(), done.errorMessage());
                    assertEquals(freshRun, done.runId());
                    assertNull(f.sessions.session(freshRun), "the retention limit may retire the newly finished run");
                    assertEquals(2, f.host.dispatched.size(), "accepted fresh action is not re-invoked");
                    assertEquals(List.of(new Probe("task-2", freshRun)), f.host.probed);
                    assertEquals(0, f.adapter.pendingCount(),
                            "fresh completion consumes its receipt; old receipt was already retired");
                });
    }

    @Test
    void purgeKeepsAPausedRunAndItsReceiptUntilOriginalTaskCompletes() {
        Fixture f = new Fixture(1);
        String runId = f.startPaused(1);
        AcxSessionManager.Session original = f.sessions.session(runId);
        assertFalse(f.runner.isRunning(runId));
        assertFalse(original.record().vars().keySet().stream()
                .anyMatch(key -> key.startsWith("_snapshot_truncated_")));
        assertEquals(1, f.adapter.pendingCount());
        assertEquals(1, f.host.dispatched.size());
        assertTrue(f.host.probed.isEmpty());

        assertEquals(0, f.sessions.purgeFinished(), "PAUSED is resumable, not terminal retirement");
        assertSame(original, f.sessions.session(runId));
        assertEquals(1, f.adapter.pendingCount(), "purge must retain the paused acceptance");
        assertEquals(1, f.host.dispatched.size());
        assertTrue(f.host.probed.isEmpty(), "purge does not resolve an active acceptance");

        f.host.completed.add("task-1");
        assertTrue(f.sessions.resume(runId, null));
        AcxRunRecord done = f.record(runId, AcxStatus.SUCCESS);
        assertSame(original, f.sessions.session(runId));
        assertEquals(runId, done.runId());
        assertEquals(List.of(new Dispatch("task-1", runId, "item-0")), f.host.dispatched);
        assertEquals(List.of(new Probe("task-1", runId)), f.host.probed);
        assertEquals(0, f.adapter.pendingCount());
    }

    private static final class Fixture {
        final ControlledHost host = new ControlledHost();
        final PortToolAdapter adapter = PortToolAdapter.builder(host).probe(host).build();
        final AcxRunner runner;
        final AcxSessionManager sessions;
        final AcxDefinition loop;
        final AcxDefinition setOnly;

        Fixture(int maxFinished) {
            AcxRegistry registry = new AcxRegistry();
            registry.register(adapter);
            loop = AcxLoader.parseJson(LOOP_JSON, registry);
            setOnly = AcxLoader.parseJson(SET_JSON, registry);
            runner = AcxRunner.builder().tools(registry).build();
            sessions = new AcxSessionManager(runner, Runnable::run, maxFinished);
        }

        String startPaused(int itemCount) {
            List<String> items = IntStream.range(0, itemCount).mapToObj(i -> "item-" + i).toList();
            String runId = sessions.start(loop, Map.of("items", items));
            AcxRunRecord paused = record(runId, AcxStatus.PAUSED);
            assertEquals(0, paused.completedStepIndex(), "pause remains on the for step");
            return runId;
        }

        String failAfterRealTruncatedSnapshot() {
            String runId = startPaused(65);
            AcxRunRecord paused = record(runId, AcxStatus.PAUSED);
            List<Object> markers = paused.vars().entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith("_snapshot_truncated_"))
                    .map(Map.Entry::getValue).toList();
            assertFalse(markers.isEmpty(), "execControl must produce the real truncation marker");
            for (Object marker : markers) {
                assertInstanceOf(Map.class, marker);
                Map<?, ?> counts = (Map<?, ?>) marker;
                assertEquals(65, ((Number) counts.get("total")).intValue());
                assertEquals(64, ((Number) counts.get("kept")).intValue());
            }
            assertEquals(List.of(new Dispatch("task-1", runId, "item-0")), host.dispatched);
            assertEquals(1, adapter.pendingCount());
            assertTrue(host.probed.isEmpty(), "first acceptance is not completion");

            assertTrue(sessions.resume(runId, null));
            AcxRunRecord failed = record(runId, AcxStatus.FAIL);
            assertEquals(runId, failed.runId());
            assertTrue(failed.errorMessage().contains("快照不完整"), failed.errorMessage());
            assertFalse(runner.isRunning(runId));
            assertFalse(sessions.resume(runId, null), "FAIL is no longer resumable");
            assertEquals(1, host.dispatched.size(), "truncation guard must not re-invoke");
            assertTrue(host.probed.isEmpty(), "truncation guard runs before receipt probing");
            assertEquals(1, adapter.pendingCount(), "guard failure leaves the acceptance for retirement");
            return runId;
        }

        AcxRunRecord record(String runId, AcxStatus expected) {
            AcxSessionManager.Session session = sessions.session(runId);
            assertNotNull(session, "session missing: " + runId);
            assertFalse(session.isRunning(), "direct executor must have completed this attempt");
            assertNull(session.error(), "unexpected session infrastructure error");
            AcxRunRecord record = session.record();
            assertNotNull(record);
            assertEquals(runId, record.runId());
            assertEquals(expected, record.status(), record.errorMessage());
            return record;
        }
    }

    private record Dispatch(String taskId, String runId, String item) { }
    private record Probe(String taskId, String runId) { }

    /** The only substitutes: deterministic host acceptance and task-id completion evidence. */
    private static final class ControlledHost implements AcxToolPort, AcxCompletionProbe {
        final List<Dispatch> dispatched = new ArrayList<>();
        final List<Probe> probed = new ArrayList<>();
        final Set<String> completed = new HashSet<>();

        @Override public String name() { return "async_action"; }

        @Override public AcxPortSchema schema() {
            return new AcxPortSchema(
                    Map.of("item", AcxPortSchema.Param.req(AcxPortSchema.Type.STRING)),
                    Set.of("item"), false);
        }

        @Override public Result invoke(Map<String, Object> params) {
            throw new AssertionError("runner must supply the original run context");
        }

        @Override public Result invoke(Map<String, Object> params, AcxCallContext ctx) {
            String taskId = "task-" + (dispatched.size() + 1);
            String item = (String) params.get("item");
            dispatched.add(new Dispatch(taskId, ctx.runId(), item));
            return Result.accepted(taskId, Map.of("item", item), "host accepted original task");
        }

        @Override public Snapshot probe(String taskId) {
            throw new AssertionError("probe must receive the original run context");
        }

        @Override public Snapshot probe(String taskId, AcxCallContext ctx) {
            probed.add(new Probe(taskId, ctx.runId()));
            Dispatch original = dispatched.stream().filter(call -> call.taskId().equals(taskId))
                    .findFirst().orElseThrow();
            assertEquals(original.runId(), ctx.runId(), "probe must not cross runs");
            return completed.contains(taskId)
                    ? Snapshot.done(Map.of("item", original.item()), "original accepted task completed")
                    : Snapshot.running("original accepted task still running");
        }
    }
}
