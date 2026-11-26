package com.dwinovo.numen.plugins.acx;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.dwinovo.numen.plugins.acx.AcxCompanionBridgeConsumerTest.PREFIX;
import static com.dwinovo.numen.plugins.acx.AcxCompanionBridgeConsumerTest.add;
import static com.dwinovo.numen.plugins.acx.AcxCompanionBridgeConsumerTest.call;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Shared with the existing bridge suite; execute as a JUnit nested suite, not a new runner.
 * The task slot, receipt ledger and restore methods are production; the world edge is a fixture.
 * Check first-tick ordering and retained recipes without claiming that normal spawn keeps them.
 */
class AcxTaskReplayFixture {
    @Test void preFirstTickAcxAcceptanceDoesNotReenterTheToolFromBodyRestore() throws Exception {
        assertEquals(List.of(true, "", true, List.of("original"), List.of("original")), call("restoreAfterEarlyAcxAcceptance"));
    }

    @Test void retiringAnAcceptedAcxTaskDoesNotReplayItOnTheNewBody() throws Exception {
        assertEquals(List.of(true, true, "PAUSED", "", List.of("original")), call("retireAcceptedAcxTask"));
    }

    @Test void acxAcceptanceAfterSlotReleaseClearsThePreviousRecipe() throws Exception {
        assertEquals(List.of(true, "prior", true, true, "", List.of("prior", "original")),
                call("admitAfterPriorSlotReleased"));
    }

    @Test void aBusyAcxAdmissionKeepsThePreviousRecipe() throws Exception {
        assertEquals(List.of(true, "PAUSED", "body_busy", true, List.of("prior", "replay:prior")),
                call("busyThenRestorePrior"));
    }

    @Test void directHostCallsStillReplayEvenWithAnAcxLookingId() throws Exception {
        assertEquals(List.of("replay:model", "replay:mcp", "replay:similar-id", "replay:owner"),
                call("restoreOrdinaryCalls"));
    }

    @Test void recoveryScopeDoesNotLeakToAnotherBodyOrToTheNextCallAfterThrow() throws Exception {
        assertEquals(List.of("during", "after", List.of("during", "after")), call("restorePolicyScope"));
    }

    static void addConsumers(List<String> args, Path root) throws Exception {
        Path api = root.resolve("api/common/src/main/java/com/dwinovo/numen");
        for (String file : List.of("agent/tool/ToolRegistry", "agent/tool/api/ToolContext",
                "task/TaskDispatch", "task/TaskPersistence", "task/TaskOwnership",
                "task/TaskState", "task/TaskResult", "task/TaskSlot", "task/Task")) {
            add(args, PREFIX + file.replace('/', '.'), Files.readString(api.resolve(file + ".java")));
        }
    }

    // Model saved data/clock and inert task effects, not the dispatch or replay decisions.
    static final Map<String,String> IO=Map.of(
        PREFIX+"entity.CompanionRegistry", """
            package com.dwinovo.numen.entity;
            import java.util.*;
            import net.minecraft.server.MinecraftServer;
            public class CompanionRegistry {
                private static final Map<MinecraftServer,CompanionRegistry> worlds = new HashMap<>();
                private final Map<UUID,Entry> entries = new HashMap<>();
                public static CompanionRegistry get(MinecraftServer server) {
                    return worlds.computeIfAbsent(server, unused -> new CompanionRegistry());
                }
                public Entry find(UUID id) { return entries.get(id); }
                public void put(UUID id, Entry entry) { entries.put(id, entry); }
                public record Entry(String taskTool, String taskArgs) {
                    public Entry doing(String tool, String args) { return new Entry(tool,args); }
                }
            }
            """,
        PREFIX+"task.TaskFactory", """
            package com.dwinovo.numen.task;
            import com.dwinovo.numen.entity.NumenPlayer;
            public class TaskFactory {
                static Task create(NumenPlayer body, TaskRecord record) {
                    return new Task() {
                        public TaskState tick(NumenPlayer p) { return TaskState.RUNNING; }
                        public void stop(NumenPlayer p, StopReason reason) {}
                        public String name() { return record.getToolName(); }
                    };
                }
            }
            """,
        PREFIX+"api.WorldContextStamp", """
            package com.dwinovo.numen.api;
            import java.util.Map;
            import net.minecraft.server.MinecraftServer;
            public class WorldContextStamp {
                public record Token(Map<String,String> identity, long epoch) {}
                public static long epoch;
                public static Token capture(MinecraftServer server) {
                    return server == null || epoch <= 0 ? null : new Token(Map.of("world_id", "fixture-world",
                            "server_session", "fixture-session"), epoch);
                }
            }
            """,
        PREFIX+"event.NumenEvents", """
            package com.dwinovo.numen.event;
            import com.dwinovo.numen.entity.NumenPlayer;
            public class NumenEvents {
                public static void taskFinished(NumenPlayer p, String id, String tool, String status, String reason) {
                    throw new AssertionError("unexpected restore failure: " + reason);
                }
            }
            """,
        "org.slf4j.LoggerFactory", """
            package org.slf4j;
            public class LoggerFactory {
                public static Logger getLogger(Class<?> type) { return new Logger(); }
                public static class Logger {
                    public void info(String message, Object... args) {}
                    public void warn(String message, Object... args) {}
                }
            }
            """,
        PREFIX+"Constants", """
            package com.dwinovo.numen;
            public class Constants {
                public static final org.slf4j.LoggerFactory.Logger LOG = org.slf4j.LoggerFactory.getLogger(Constants.class);
            }
            """
    );

    static final String SCENARIOS = """
            static class ReplayFixture {
                final Fixture bridge = new Fixture(true);
                final List<String> starts = new ArrayList<>();
                final List<String> entered = new ArrayList<>();
                final List<String> callIds = new ArrayList<>();
                final CompanionRegistry saved = CompanionRegistry.get(bridge.a.getServer());
                final PortToolAdapter adapter;
                final NumenTool tool = new NumenTool() {
                    public String name() { return "replay_fixture"; }
                    public Map<String,Object> parameterSchema() {
                        return Map.of("type", "object", "properties", Map.of("sample", Map.of("type", "string")));
                    }
                    public void onServerCall(String id, JsonObject args, NumenPlayer p, Consumer<String> reply) {
                        String sample = (id.startsWith("restored-") ? "replay:" : "") + args.get("sample").getAsString();
                        entered.add(sample);
                        callIds.add(id);
                        if ("throw".equals(sample)) {
                            if (!direct(id, "during", bridge.b)) throw new AssertionError("second body not accepted");
                            throw new IllegalStateException("fixture tool threw before seating a task");
                        }
                        var record = task(id);
                        TaskDispatch.setTask(p, record, args, raw -> {
                            if (JsonParser.parseString(raw).getAsJsonObject().get("success").getAsBoolean()) {
                                starts.add(sample);
                            }
                            reply.accept(raw);
                        });
                    }
                };
                ReplayFixture() { this("original"); }
                ReplayFixture(String stepInput) {
                    saved.put(bridge.a.getUUID(), new CompanionRegistry.Entry("", ""));
                    saved.put(bridge.b.getUUID(), new CompanionRegistry.Entry("", ""));
                    ToolRegistry.register(tool);
                    var registry = new AcxRegistry();
                    adapter = PortToolAdapter.builder(new NumenToolBridgeX(tool, bridge.plugin, 100))
                            .probe(NumenToolBridgeX.probeFor(bridge.plugin)).build();
                    registry.register(adapter);
                    var definition = new AcxDefinition("binding", "1", "", List.of(),
                            List.of(new AcxStep("body", tool.name(), Map.of("sample", stepInput))), "", "");
                    bridge.plugin.sessions = new AcxSessionManager(AcxRunner.builder().tools(registry).build(), bridge.plugin.queue::add);
                    bridge.plugin.facade = new AcxFacade(bridge.plugin.sessions, name -> definition, registry, null);
                }
                String sample() { return sample(bridge.a); }
                String sample(NumenPlayer body) {
                    var entry = saved.find(body.getUUID());
                    return entry.taskTool().isBlank() ? "" : JsonParser.parseString(entry.taskArgs())
                            .getAsJsonObject().get("sample").getAsString();
                }
                boolean direct(String callId, String sample, NumenPlayer body) {
                    JsonObject args = new JsonObject();
                    args.addProperty("sample", sample);
                    boolean[] accepted = {false};
                    tool.onServerCall(callId, args, body,
                            raw -> accepted[0] = JsonParser.parseString(raw).getAsJsonObject().get("success").getAsBoolean());
                    return accepted[0];
                }
                AcxSessionManager.Session dispatch() {
                    bridge.start(); bridge.drain();
                    return bridge.plugin.sessions.session(bridge.run);
                }
                boolean pausedOnReceipt(AcxSessionManager.Session run) {
                    return run.record().status() == AcxStatus.PAUSED && run.record().completedStepIndex() == 0
                            && adapter.pendingCount() == 1;
                }
                NumenPlayer retire() {
                    bridge.plugin.sessions.invalidateBody(bridge.a);
                    bridge.drain();
                    CompanionTickDispatcher.removeBody(bridge.a);
                    return new NumenPlayer(bridge.a.name);
                }
                List<String> measuredStarts() { return List.copyOf(starts); }
                void close() {
                    CompanionTickDispatcher.removeBody(bridge.a);
                    CompanionTickDispatcher.removeBody(bridge.b);
                    ToolRegistry.remove(tool.name());
                }
            }
            public static List<Object> restorePolicyScope() {
                var f = new ReplayFixture("throw");
                try {
                    var run = f.dispatch();
                    if (!f.pausedOnReceipt(run) || !f.entered.equals(List.of("throw", "during"))) {
                        throw new AssertionError("the scoped call did not reach the controlled exception");
                    }
                    if (!f.direct(f.callIds.getFirst(), "after", f.bridge.a)) {
                        throw new AssertionError("the next call on the same body was not accepted");
                    }
                    return List.of(f.sample(f.bridge.b), f.sample(), f.measuredStarts());
                } finally { f.close(); }
            }

            // Cold tool delivery can accept on the new body between spawn and its first tick.
            // Model the fresh registry entry and call the exact restore invoked on the first tick.
            public static List<Object> restoreAfterEarlyAcxAcceptance() {
                var f = new ReplayFixture();
                try {
                    var run = f.dispatch();
                    var id = f.bridge.a.getUUID();
                    var task = CompanionTickDispatcher.currentTaskFor(id);
                    boolean accepted = task != null && run.ownsTask(task.publicId()) && f.pausedOnReceipt(run);
                    String beforeRestore = f.sample();
                    TaskPersistence.restore(f.bridge.a);
                    return List.of(accepted, beforeRestore, task != null && task == CompanionTickDispatcher.currentTaskFor(id),
                            List.copyOf(f.entered), f.measuredStarts());
                } finally { f.close(); }
            }
            // Adversarial retained-row controls: normal CompanionFactory.spawn currently clears the row.
            public static List<Object> retireAcceptedAcxTask() {
                var f = new ReplayFixture();
                try {
                    var original = f.dispatch();
                    var task = CompanionTickDispatcher.currentTaskFor(f.bridge.a.getUUID());
                    boolean accepted = task != null && original.ownsTask(task.publicId()) && f.pausedOnReceipt(original);
                    var replacement = f.retire();
                    String beforeRestore = f.sample();
                    TaskPersistence.restore(replacement);
                    return List.of(accepted, accepted && original.isBodyInvalidated() && f.adapter.pendingCount() == 0
                            && !original.ownsTask(task.publicId()) && f.bridge.a != replacement
                            && f.bridge.a.getUUID().equals(replacement.getUUID()),
                            original.record().status().name(), beforeRestore, f.measuredStarts());
                } finally { f.close(); }
            }
            public static List<Object> admitAfterPriorSlotReleased() {
                var f = new ReplayFixture();
                try {
                    boolean prior = f.direct("mcp-wire-prior", "prior", f.bridge.a);
                    var priorTask = CompanionTickDispatcher.currentTaskFor(f.bridge.a.getUUID());
                    String priorRecipe = f.sample();
                    // The original task has finished; deliberately retain its stale recipe in this fixture.
                    CompanionTickDispatcher.settle(f.bridge.a, priorTask, TaskState.SUCCESS);
                    var original = f.dispatch();
                    var task = CompanionTickDispatcher.currentTaskFor(f.bridge.a.getUUID());
                    boolean accepted = task != null && original.ownsTask(task.publicId()) && f.pausedOnReceipt(original);
                    boolean preserved = accepted && task != priorTask && priorTask.getState() == TaskState.SUCCESS;
                    var replacement = f.retire();
                    String beforeRestore = f.sample();
                    TaskPersistence.restore(replacement);
                    return List.of(prior, priorRecipe, accepted, preserved, beforeRestore, f.measuredStarts());
                } finally { f.close(); }
            }
            public static List<Object> busyThenRestorePrior() {
                var f = new ReplayFixture();
                try {
                    boolean prior = f.direct("mcp-wire-prior", "prior", f.bridge.a);
                    var priorTask = CompanionTickDispatcher.currentTaskFor(f.bridge.a.getUUID());
                    f.bridge.a.gameTime += 21; // Busy admission must also protect a non-AC task past grace.
                    var original = f.dispatch();
                    boolean kept = "prior".equals(f.sample()) && priorTask != null
                            && priorTask == CompanionTickDispatcher.currentTaskFor(f.bridge.a.getUUID());
                    TaskPersistence.restore(f.retire());
                    return List.of(prior, original.record().status().name(), original.record().pausedReason(),
                            kept, f.measuredStarts());
                } finally { f.close(); }
            }
            public static List<String> restoreOrdinaryCalls() {
                var f = new ReplayFixture();
                try {
                    for (var input : List.of(List.of("wire-model", "model"), List.of("mcp-wire-external", "mcp"),
                            List.of("mcp-acx-looks-like-a-script", "similar-id"), List.of("owner-panel", "owner"))) {
                        var body = new NumenPlayer("restore-" + input.get(1));
                        f.saved.put(body.getUUID(), new CompanionRegistry.Entry("", ""));
                        if (!f.direct(input.get(0), input.get(1), body)) throw new AssertionError("first call not accepted");
                        CompanionTickDispatcher.removeBody(body);
                        f.starts.clear();
                        TaskPersistence.restore(new NumenPlayer(body.name));
                        if (!f.starts.equals(List.of("replay:" + input.get(1)))) throw new AssertionError(f.starts);
                        CompanionTickDispatcher.removeBody(body);
                    }
                    return List.of("replay:model", "replay:mcp", "replay:similar-id", "replay:owner");
                } finally { f.close(); }
            }
            """;
}
