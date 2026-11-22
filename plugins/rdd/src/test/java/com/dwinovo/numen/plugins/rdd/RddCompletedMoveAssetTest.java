package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.task.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real projector, task record, registry and public detail dispatcher; controlled identity/result, no game or event bus. */
class RddCompletedMoveAssetTest {
    static class Record extends TaskRecord { Record() { super("goto", "fixture", 100); } }
    static Map<String,Object> fact() {
        return Map.of("world_id", "world", "server_session", "session", "epoch", 7L,
                "dimension", "overworld", "direction", "start_to_end_only", "route_proven", false,
                "start", Map.of("x", 1.0, "y", 64.0, "z", 2.0), "end", Map.of("x", 5.0, "y", 64.0, "z", 2.0));
    }
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;

    @Test void actualMoveProducerMethodsReachRegistryAndDetail() throws Exception {
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.isRegularFile(root.resolve("core/common/src/test/java/com/dwinovo/numen/core/task/move/MoveCompletedFactTest.java"))) root = root.getParent();
        assertNotNull(root);
        String fixture = java.nio.file.Files.readString(root.resolve("core/common/src/test/java/com/dwinovo/numen/core/task/move/MoveCompletedFactTest.java"));
        String source = java.nio.file.Files.readString(root.resolve("core/common/src/main/java/com/dwinovo/numen/core/task/move/MoveToCompanionTask.java"));
        String stamp = java.nio.file.Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/api/WorldContextStamp.java"));
        String start = "add(src,args,\"fixture/Probe.java\", \"\"\"";
        int a = fixture.indexOf(start), b = fixture.indexOf("        var compiler=", a);
        assertTrue(a >= 0 && b > a, "SETUP: producer fixture missing");
        // The fixture itself uses the original production method slices; execute it in an isolated loader.
        java.nio.file.Path src = temp.resolve("src"), out = temp.resolve("out"); java.nio.file.Files.createDirectories(out);
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-proc:none"));
        add(src,args,"net/minecraft/server/MinecraftServer.java", "package net.minecraft.server; public class MinecraftServer {} ");
        add(src,args,"com/dwinovo/numen/api/ServerIdentityProvider.java", "package com.dwinovo.numen.api; import net.minecraft.server.MinecraftServer; public interface ServerIdentityProvider { record Snapshot(String worldId,String serverSession) {} Snapshot snapshot(MinecraftServer s); }");
        add(src,args,"com/dwinovo/numen/api/NumenPlugins.java", "package com.dwinovo.numen.api; public class NumenPlugins { public static ServerIdentityProvider provider; public static <T> T capability(Class<T> c) { return c.cast(provider); } }");
        add(src,args,"com/dwinovo/numen/api/WorldContextStamp.java",stamp);
        String javaCode = fixture.substring(a, b);
        // Generate probe via the checked-in source fixture, preserving its literal prelude and scenarios.
        String prefix = javaCode.substring(javaCode.indexOf("\"\"\"") + 3, javaCode.indexOf("\"\"\" + member"));
        String suffix = javaCode.substring(javaCode.indexOf("+ \"\"\"", javaCode.indexOf("resultData(TaskState state)")) + 7, javaCode.lastIndexOf("\"\"\""));
        add(src,args,"fixture/Probe.java", prefix + member(source,"    protected Map<String, Object> resultData()") + "\n"
                + member(source,"    private void captureMoveStart()") + "\n" + member(source,"    protected Map<String, Object> resultData(TaskState state)") + "\n" + suffix);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)), "SETUP: producer slice compile");
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{out.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            var run = loader.loadClass("fixture.Probe").getMethod("run", String.class);
            @SuppressWarnings("unchecked") var result = (Map<String,Object>)run.invoke(null, "ok");
            @SuppressWarnings("unchecked") var produced = (Map<String,Object>)result.get("completed_move");
            assertNotNull(produced);
            var token = new WorldContextStamp.Token(Map.of("world_id", "world", "server_session", "session"), ((Number)produced.get("epoch")).longValue());
            var record = new Record(); record.bindIdentity(token); record.setState(TaskState.SUCCESS); record.setResult(TaskResult.ok("done", result));
            var registry = new AssetRegistry(); assertTrue(RddCompletedMoveAsset.project(UUID.randomUUID(), record.publicId(), record, token, "overworld", registry, 100));
            var query = new JsonObject(); query.addProperty("action", "detail"); query.addProperty("asset_id", registry.snapshot().getFirst().assetId());
            String detail = RddAssetsTool.dispatch(query, registry);
            assertTrue(detail.contains("start_to_end_only") && detail.contains("route_proven") && detail.contains("false"), detail);
            @SuppressWarnings("unchecked") var failed = (Map<String,Object>)run.invoke(null, "failed");
            assertFalse(failed.containsKey("completed_move"));
        }
    }
    private static String member(String source, String signature) {
        int a = source.indexOf(signature), b = source.indexOf("\n    }", a);
        if (a < 0 || b < 0) throw new IllegalStateException("missing member " + signature);
        return source.substring(a, b + 6);
    }
    private static void add(java.nio.file.Path root, List<String> args, String name, String text) throws Exception {
        var file = root.resolve(name); java.nio.file.Files.createDirectories(file.getParent()); java.nio.file.Files.writeString(file, text); args.add(file.toString());
    }

    @Test void successProjectsOnceAndDetailRemainsHistoricalEndpoints() {
        UUID id = UUID.randomUUID(); var token = new WorldContextStamp.Token(Map.of("world_id", "world", "server_session", "session"), 7);
        var record = new Record(); record.bindIdentity(token); record.setState(TaskState.SUCCESS);
        record.setResult(TaskResult.ok("done", Map.of("completed_move", fact())));
        var registry = new AssetRegistry();
        assertTrue(RddCompletedMoveAsset.project(id, record.publicId(), record, token, "overworld", registry, 100));
        assertFalse(RddCompletedMoveAsset.project(id, record.publicId(), record, token, "overworld", registry, 200));
        assertEquals(RddCompletedMoveAsset.Outcome.EXISTING, RddCompletedMoveAsset.projectOutcome(id, record.publicId(), record, token, "overworld", registry, 200));
        assertEquals(100, registry.snapshot().getFirst().observation().observedAt());
        assertEquals(1, registry.snapshot().size());
        var args = new JsonObject(); args.addProperty("action", "detail"); args.addProperty("asset_id", registry.snapshot().getFirst().assetId());
        String detail = RddAssetsTool.dispatch(args, registry);
        assertTrue(JsonParser.parseString(detail).getAsJsonObject().get("success").getAsBoolean(), detail);
        assertTrue(detail.contains("start_to_end_only") && detail.contains("route_proven") && detail.contains("false"), detail);
        assertTrue(detail.contains("not a continuous track") && detail.contains("world_id"), detail);
    }
    @Test void plannerReceivesDirectedEndpointsOrExplicitUndelivered() {
        UUID id = UUID.randomUUID(); var token = new WorldContextStamp.Token(Map.of("world_id", "world", "server_session", "session"), 7);
        var record = new Record(); record.bindIdentity(token); record.setState(TaskState.SUCCESS);
        record.setResult(TaskResult.ok("done", Map.of("completed_move", fact())));
        var registry = new AssetRegistry();
        assertTrue(RddCompletedMoveAsset.project(id, record.publicId(), record, token, "overworld", registry, 100));
        String summary = RddAssetContext.renderForPlanner(registry, 160);
        assertTrue(summary.length() <= 160, summary);
        List<String> sent = new ArrayList<>();
        var snapshot = com.dwinovo.numen.rdd.core.PlanningAssetSnapshot.from(Map.of(), 100L, registry);
        RddStagePlanner.dispatchStages(id, "fixture objective", null, snapshot, summary,
                (companion, objective, stage, failures) -> "", () -> "", sent::add);
        assertEquals(1, sent.size());
        assertTrue(sent.getFirst().contains(summary), sent.getFirst());
        assertAll(
                () -> assertTrue(summary.contains("1.0,64.0,2.0") && summary.contains("5.0,64.0,2.0"), summary),
                () -> assertTrue(summary.contains("→") && summary.contains("非通路证明"), summary));
        String tiny = RddAssetContext.renderForPlanner(registry, 4);
        assertTrue(tiny.equals("未投递") || tiny.isEmpty(), tiny);
    }

    @Test void failedSaveThenDuplicateEventMustBeRecoverable() throws Exception {
        UUID id = UUID.randomUUID(); var token = new WorldContextStamp.Token(Map.of("world_id", "world", "server_session", "session"), 7);
        var record = new Record(); record.bindIdentity(token); record.setState(TaskState.SUCCESS);
        record.setResult(TaskResult.ok("done", Map.of("completed_move", fact())));
        var registry = new AssetRegistry();
        // Mirror the production callback's project-then-save branch; storage calls are real.
        var notDirectory = temp.resolve("not-a-directory"); java.nio.file.Files.writeString(notDirectory, "file");
        assertTrue(RddCompletedMoveAsset.project(id, record.publicId(), record, token, "overworld", registry, 100));
        assertThrows(java.io.IOException.class, () -> RddAssetStore.save(notDirectory, id, registry));
        var recoveredDirectory = temp.resolve("recovered");
        if (RddCompletedMoveAsset.projectOutcome(id, record.publicId(), record, token, "overworld", registry, 101)
                != RddCompletedMoveAsset.Outcome.REJECTED)
            RddAssetStore.save(recoveredDirectory, id, registry);
        var restored = RddAssetStore.load(recoveredDirectory, id);
        assertEquals(1, restored.snapshot().size(), "same terminal event after a failed save must be recoverable");
    }

    @Test void burstWithoutObserverKeepsMoveBoundAndOtherAssets() {
        UUID id = UUID.randomUUID(); var token = new WorldContextStamp.Token(Map.of("world_id", "world", "server_session", "session"), 7);
        var registry = new AssetRegistry();
        registry.apply(new com.dwinovo.numen.rdd.api.Observation("base", "world_base", "fixture", "overworld", 1, Map.of("kind", "base")), "base", com.dwinovo.numen.rdd.api.AssetScope.GLOBAL, null);
        registry.apply(new com.dwinovo.numen.rdd.api.Observation("other", "world_machine", "fixture", "overworld", 1, Map.of("kind", "machine")), "other", com.dwinovo.numen.rdd.api.AssetScope.GLOBAL, null);
        for (int i = 0; i < 34; i++) {
            var record = new Record(); record.bindIdentity(token); record.setState(TaskState.SUCCESS);
            record.setResult(TaskResult.ok("done", Map.of("completed_move", fact())));
            assertTrue(RddCompletedMoveAsset.project(id, record.publicId(), record, token, "overworld", registry, 100 + i));
        }
        assertAll(
                () -> assertEquals(32, registry.snapshot().stream().filter(e -> e.observation().type().equals("world_completed_move")).count()),
                () -> assertTrue(registry.get("base").isPresent()),
                () -> assertTrue(registry.get("other").isPresent()));
    }

    @Test void failedUnknownWrongIdentityAndNonfiniteNeverProject() {
        UUID id = UUID.randomUUID(); var token = new WorldContextStamp.Token(Map.of("world_id", "world", "server_session", "session"), 7);
        var record = new Record(); record.bindIdentity(token); record.setState(TaskState.SUCCESS);
        var registry = new AssetRegistry(); record.setResult(TaskResult.ok("done", Map.of("completed_move", fact())));
        assertFalse(RddCompletedMoveAsset.project(id, record.publicId(), record, null, "overworld", registry, 100));
        assertFalse(RddCompletedMoveAsset.project(id, record.publicId(), record, token, "nether", registry, 100));
        assertFalse(RddCompletedMoveAsset.project(id, "other", record, token, "overworld", registry, 100));
        assertFalse(RddCompletedMoveAsset.project(id, record.publicId(), record, new WorldContextStamp.Token(token.identity(), 8), "overworld", registry, 100));
        var bad = new HashMap<>(fact()); bad.put("end", Map.of("x", Double.NaN, "y", 64, "z", 0));
        record.setResult(TaskResult.ok("done", Map.of("completed_move", bad)));
        assertFalse(RddCompletedMoveAsset.project(id, record.publicId(), record, token, "overworld", registry, 100));
        record.setState(TaskState.CANCELLED); record.setResult(TaskResult.cancelled("cancel"));
        assertFalse(RddCompletedMoveAsset.project(id, record.publicId(), record, token, "overworld", registry, 100));
        assertTrue(registry.snapshot().isEmpty());
    }
}
