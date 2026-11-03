package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AdvancementVerification;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.dwinovo.numen.rdd.core.AdvancementVerification.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Offline regression for advancement evidence and the two diagnostic JSON responses.
 * Run: gradle --offline :plugins:rdd:test --tests '*AdvancementTickWiringRegressionTest'.
 * Extracts current production methods and compiles the real TaskResult source; core is real.
 * Only Minecraft/host boundaries are stubbed: this is NOT a Minecraft or scheduler test.
 * A hand-copied detector would miss production wiring regressions. The test requires a JDK,
 * the source tree, Gson, and rdd-core; it does not deploy or award real advancements.
 */
@Execution(ExecutionMode.SAME_THREAD)
class AdvancementTickWiringRegressionTest {
    private static final String ID = "minecraft:story/obtain_armor";
    private static final String CONDITION = "{\"type\":\"advancement\",\"advancement\":\"" + ID + "\"}";

    @TempDir static Path output;
    private static URLClassLoader loader;
    private static Class<?> host;

    @BeforeAll
    static void compileActualMethods() throws Exception {
        Path root = root();
        Path plugin = root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
        String world = Files.readString(plugin.resolve("RddWorldFacts.java"), StandardCharsets.UTF_8);
        String probe = Files.readString(plugin.resolve("WorldFactProbeTool.java"), StandardCharsets.UTF_8);
        String status = Files.readString(plugin.resolve("RddStatusTool.java"), StandardCharsets.UTF_8);
        String harness = HOST_HEAD + "\nstatic class RddWorldFacts {\n"
                + method(world, "    private static ResourceLocation id(") + "\n"
                + method(world, "    private static boolean dimension(") + "\n"
                + method(world, "    static com.dwinovo.numen.rdd.core.AdvancementVerification.Result advancementResult(") + "\n"
                + WORLD_SEAMS + "\n}\nstatic class WorldFactProbeTool {\n"
                + "static final Gson GSON = new Gson();\n"
                + method(probe, "    public String description()") + "\n"
                + method(probe, "    public void onServerCall(") + "\n}\n"
                + "static class RddStatusTool {\n"
                + method(status, "    @Override public String description()") + "\n"
                + method(status, "    public void onServerCall(") + "\n}\n"
                + HOST_TAIL;
        // The source has an @Override on the status description. This isolated host has
        // no NumenTool dependency, so supply only the declaration inherited by that method.
        harness = harness.replace("static class RddStatusTool {\n", "static class RddStatusTool extends ToolDescription {\n");
        Path java = output.resolve("AdvancementDiagnosticHost.java");
        Files.writeString(java, harness, StandardCharsets.UTF_8);
        Path taskResult = root.resolve("api/common/src/main/java/com/dwinovo/numen/task/TaskResult.java");
        String classpath = location(AdvancementVerification.class) + File.pathSeparator + location(Gson.class)
                + File.pathSeparator + System.getProperty("java.class.path");
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK required to compile current production sources");
        int result = compiler.run(null, null, null, "-encoding", "UTF-8", "-proc:none", "-classpath", classpath,
                "-d", output.toString(), java.toString(), taskResult.toString());
        assertEquals(0, result, "isolated host could not compile extracted production methods");
        loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, AdvancementTickWiringRegressionTest.class.getClassLoader());
        host = loader.loadClass("AdvancementDiagnosticHost");
        assertEquals(output.toUri().toURL(), loader.loadClass("com.dwinovo.numen.task.TaskResult")
                .getProtectionDomain().getCodeSource().getLocation(), "must use compiled production TaskResult source");
    }

    @AfterAll
    static void closeLoader() throws Exception {
        if (loader != null) loader.close();
    }

    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static Path root() {
        String override = System.getProperty("rdd.repo.root");
        if (override != null) return Path.of(override).toAbsolutePath();
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddWorldFacts.java"))) return p;
        }
        throw new IllegalStateException("repository root not found; set -Drdd.repo.root");
    }

    /** Extract one unchanged method, ignoring braces in comments and normal string/char literals. */
    private static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "production method missing: " + signature);
        assertEquals(start, source.lastIndexOf(signature), "production method is not unique: " + signature);
        int opening = source.indexOf('{', start);
        assertTrue(opening >= 0, "production method has no body: " + signature);
        int depth = 0;
        boolean line = false, block = false, string = false, character = false, escaped = false;
        for (int i = opening; i < source.length(); i++) {
            char c = source.charAt(i), next = i + 1 < source.length() ? source.charAt(i + 1) : 0;
            if (line) { if (c == '\n') line = false; continue; }
            if (block) { if (c == '*' && next == '/') { block = false; i++; } continue; }
            if (string || character) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if ((string && c == '"') || (character && c == '\'')) { string = false; character = false; }
                continue;
            }
            if (c == '/' && next == '/') { line = true; i++; continue; }
            if (c == '/' && next == '*') { block = true; i++; continue; }
            if (c == '"') { string = true; continue; }
            if (c == '\'') { character = true; continue; }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return source.substring(start, i + 1);
        }
        throw new AssertionError("unterminated production method: " + signature);
    }

    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        try { return host.getMethod(name, types).invoke(null, args); }
        catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            if (ex.getCause() instanceof Error cause) throw cause;
            throw ex;
        }
    }
    private static void reset(String scenario, boolean ordinary) throws Exception {
        call("reset", new Class<?>[]{String.class, boolean.class}, scenario, ordinary);
    }
    private static String invoke(String name, String input) throws Exception {
        return (String) call(name, new Class<?>[]{String.class}, input);
    }
    private static int count(String name) throws Exception {
        return (int) call("count", new Class<?>[]{String.class}, name);
    }
    private static JsonObject data(String response) {
        JsonObject json = JsonParser.parseString(response).getAsJsonObject();
        assertTrue(json.get("success").getAsBoolean(), response);
        return json.getAsJsonObject("data");
    }
    private static void unknownOrAbsent(JsonObject data, String field) {
        assertTrue(!data.has(field) || data.get(field).isJsonNull(), () -> field + " must not be true/false: " + data);
    }

    @Test
    void realCoreKeepsLookupFailuresAndInterruptionUnknown() {
        assertEquals(INVALID, AdvancementVerification.evaluate(ID, id -> Optional.empty(), null));
        assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate(ID, id -> null, item -> true));
        assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate(ID, id -> { throw new Exception("registry"); }, item -> true));
        assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate(ID, id -> Optional.of(id), item -> null));
        assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate(ID, id -> Optional.of(id), item -> { throw new Exception("progress"); }));
        try {
            assertEquals(NOT_VERIFIABLE, AdvancementVerification.evaluate(ID, id -> { throw new InterruptedException(); }, item -> true));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test
    void fourStatesReachBothActualToolJsonResponses() throws Exception {
        for (String state : List.of("MATCHED", "NOT_MATCHED", "INVALID", "NOT_VERIFIABLE")) {
            String scenario = state.equals("NOT_VERIFIABLE") ? "OFF_THREAD" : state;
            String input = state.equals("INVALID")
                    ? "{\"type\":\"advancement\",\"advancement\":42}" : CONDITION;
            reset(scenario, false);
            assertEquals(state, invoke("verify", input));
            JsonObject probe = data(invoke("probe", input));
            JsonObject status = data(invoke("status", input));
            assertEquals(state, probe.get("verification_status").getAsString());
            assertEquals(state, status.get("verification_status").getAsString());
            assertEquals("advancement", status.get("verification_scope").getAsString());
            assertFalse(status.has("execution_note"));
            assertEquals(!state.equals("INVALID"), probe.get("shape_valid").getAsBoolean());
            if (state.equals("MATCHED") || state.equals("NOT_MATCHED")) {
                boolean expected = state.equals("MATCHED");
                assertTrue(probe.get("valid").getAsBoolean());
                assertEquals(expected, probe.get("matched").getAsBoolean());
                assertEquals(expected, status.get("condition_matches").getAsBoolean());
                assertTrue(count("progress") > 0, state + " must consult registered progress");
            } else {
                unknownOrAbsent(probe, "matched");
                unknownOrAbsent(status, "condition_matches");
                if (state.equals("INVALID")) assertFalse(probe.get("valid").getAsBoolean());
                else unknownOrAbsent(probe, "valid");
                assertEquals(0, count("progress"), state + " must not read progress");
            }
        }
    }

    @Test
    void malformedAdvancementShapesAreInvalidNotFalse() throws Exception {
        for (String input : List.of(
                "{\"type\":\"advancement\",\"advancement\":42}",
                "{\"type\":\"advancement\",\"advancement\":null}",
                "{\"type\":\"advancement\"}",
                "{\"type\":\"advancement\",\"advancement\":\"bad id\"}",
                "{\"type\":\"advancement\",\"advancement\":\"minecraft:ok\",\"dimension\":42}")) {
            reset("MATCHED", true);
            assertEquals("INVALID", invoke("verify", input));
            JsonObject probe = data(invoke("probe", input));
            assertEquals("INVALID", probe.get("verification_status").getAsString());
            assertFalse(probe.get("shape_valid").getAsBoolean());
            unknownOrAbsent(probe, "matched");
            // Map.copyOf in the real Subtask rejects null, so test status with non-null shapes.
            if (!input.contains(":null")) {
                JsonObject status = data(invoke("status", input));
                assertEquals("INVALID", status.get("verification_status").getAsString());
                unknownOrAbsent(status, "condition_matches");
            }
            assertEquals(0, count("progress"));
        }
        reset("MATCHED", true);
        JsonObject wrongType = data(invoke("probe", "{\"type\":42,\"advancement\":\"" + ID + "\"}"));
        assertFalse(wrongType.has("verification_status"));
        assertFalse(wrongType.get("valid").getAsBoolean());
        assertFalse(wrongType.get("matched").getAsBoolean()); // ordinary malformed-condition path
        assertEquals(0, count("registry"));
    }

    @Test
    void unavailableServerRegistryAndProgressStayUnknownInJson() throws Exception {
        for (String scenario : List.of("SERVER_NULL", "SERVER_THROW", "OFF_THREAD", "REGISTRY_NULL",
                "REGISTRY_THROW", "LOOKUP_THROW", "MISSING", "PROGRESS_THROW", "IS_DONE_THROW")) {
            reset(scenario, false);
            assertEquals("NOT_VERIFIABLE", invoke("verify", CONDITION), scenario);
            JsonObject probe = data(invoke("probe", CONDITION));
            JsonObject status = data(invoke("status", CONDITION));
            assertEquals("NOT_VERIFIABLE", probe.get("verification_status").getAsString(), scenario);
            assertEquals("NOT_VERIFIABLE", status.get("verification_status").getAsString(), scenario);
            unknownOrAbsent(probe, "matched");
            unknownOrAbsent(status, "condition_matches");
            if (List.of("SERVER_NULL", "SERVER_THROW", "OFF_THREAD", "REGISTRY_NULL", "REGISTRY_THROW", "LOOKUP_THROW", "MISSING").contains(scenario))
                assertEquals(0, count("progress"), scenario);
        }
        reset("MATCHED", false);
        assertEquals("INVALID", invoke("verify", null));
        assertEquals("INVALID", invoke("verify", "{\"type\":\"dimension\",\"dimension\":\"minecraft:overworld\"}"));
        reset("OFF_THREAD", false);
        assertEquals("INVALID", invoke("verify", "{\"type\":\"advancement\",\"advancement\":42}"));
        assertEquals(0, count("registry"));
    }

    @Test
    void dimensionGuardCannotHideMissingOrUnknownEvidence() throws Exception {
        String otherDimension = "{\"type\":\"advancement\",\"advancement\":\"" + ID
                + "\",\"dimension\":\"minecraft:the_nether\"}";
        for (String scenario : List.of("MISSING", "LOOKUP_THROW", "MATCHED", "NOT_MATCHED", "LEVEL_NULL")) {
            reset(scenario, false);
            String expected = switch (scenario) {
                case "MISSING", "LOOKUP_THROW", "LEVEL_NULL" -> "NOT_VERIFIABLE";
                default -> "NOT_MATCHED";
            };
            assertEquals(expected, invoke("verify", otherDimension), scenario);
        }
    }

    @Test
    void crossDimensionStatusIsDiagnosticNotLegacyExecutionVerdict() throws Exception {
        String other = "{\"type\":\"advancement\",\"advancement\":\"" + ID
                + "\",\"dimension\":\"minecraft:the_nether\"}";
        for (String scenario : List.of("MATCHED", "NOT_MATCHED", "MISSING", "OFF_THREAD")) {
            reset(scenario, false);
            String expected = switch (scenario) {
                case "MISSING", "OFF_THREAD" -> "NOT_VERIFIABLE";
                default -> "NOT_MATCHED";
            };
            assertEquals(expected, invoke("verify", other));
            JsonObject probe = data(invoke("probe", other));
            assertEquals(expected, probe.get("verification_status").getAsString());
            if (expected.equals("NOT_MATCHED")) assertFalse(probe.get("matched").getAsBoolean());
            else unknownOrAbsent(probe, "matched");
            JsonObject status = data(invoke("status", other));
            assertEquals(expected, status.get("verification_status").getAsString());
            assertEquals("advancement_and_dimension", status.get("verification_scope").getAsString());
            assertFalse(status.has("condition_matches"), status.toString());
            String note = status.get("execution_note").getAsString().toLowerCase(java.util.Locale.ROOT);
            assertTrue(note.contains("legacy detector") && note.contains("dimension")
                    && note.contains("diagnostic"), note);
            assertEquals(0, count("detector"), "diagnostic does not call legacy detector");
        }
        // With the same completed progress and no dimension constraint, retain normal reporting.
        reset("MATCHED", false);
        JsonObject normal = data(invoke("status", CONDITION));
        assertEquals("MATCHED", normal.get("verification_status").getAsString());
        assertEquals("advancement", normal.get("verification_scope").getAsString());
        assertTrue(normal.get("condition_matches").getAsBoolean());
        assertFalse(normal.has("execution_note"));
    }

    @Test
    void nonAdvancementKeepsExistingToolPaths() throws Exception {
        String other = "{\"type\":\"y_below\",\"y\":10}";
        for (boolean matched : List.of(false, true)) {
            reset("MATCHED", matched);
            JsonObject probe = data(invoke("probe", other));
            assertTrue(probe.get("valid").getAsBoolean());
            assertEquals(matched, probe.get("matched").getAsBoolean());
            assertFalse(probe.has("verification_status"));
            assertEquals(1, count("unresolved"));
            assertEquals(1, count("worldMatches"));
            JsonObject status = data(invoke("status", other));
            assertEquals(matched, status.get("condition_matches").getAsBoolean());
            assertFalse(status.has("verification_status"));
            assertEquals(1, count("detector"));
            assertEquals(0, count("registry"));
        }
        reset("UNKNOWN_TAG", true);
        JsonObject tagged = data(invoke("probe", "{\"type\":\"block_nearby\",\"block\":\"#test:missing\"}"));
        assertFalse(tagged.get("valid").getAsBoolean());
        assertFalse(tagged.get("matched").getAsBoolean());
        assertEquals("#test:missing", tagged.getAsJsonArray("unknown_tags").get(0).getAsString());
        assertEquals(0, count("worldMatches"));
        assertEquals(0, count("registry"));
    }

    @Test
    void bothDescriptionsDisclosePossibleInMemoryProgressInitialization() throws Exception {
        for (String tool : List.of("probeDescription", "statusDescription")) {
            String description = (String) call(tool, new Class<?>[0]);
            String lower = description.toLowerCase(java.util.Locale.ROOT);
            assertTrue(lower.contains("advancement") && lower.contains("progress")
                    && lower.contains("initializ") && lower.contains("memory"), tool + ": " + description);
        }
        String status = ((String) call("statusDescription", new Class<?>[0])).toLowerCase(java.util.Locale.ROOT);
        assertTrue(status.contains("dimension") && status.contains("legacy detector")
                && status.contains("diagnostic") && status.contains("does not block execution"), status);
    }

    @Test
    void stubShowsDiagnosticUsesGetOrStartRatherThanProvingReadOnly() throws Exception {
        reset("NOT_MATCHED", false);
        data(invoke("probe", CONDITION));
        assertEquals(1, count("progress"));
        assertEquals(1, count("initialized"));
        data(invoke("status", CONDITION));
        assertEquals(2, count("progress"));
        assertEquals(1, count("initialized")); // fixture behavior only; not a real Minecraft experiment
    }

    private static final String HOST_HEAD = """
            import java.util.*;
            import java.util.function.Consumer;
            import com.google.gson.*;
            import com.dwinovo.numen.rdd.api.*;
            import com.dwinovo.numen.rdd.core.*;
            import com.dwinovo.numen.task.TaskResult;
            public class AdvancementDiagnosticHost {
                static String scenario;
                static boolean ordinary;
                static int registry, progress, initialized, unresolved, worldMatches, detector;
                static NumenPlayer player;
                static RddRuntime runtime;
                static class ToolDescription { public String description() { return ""; } }
                static class ResourceLocation {
                    final String id;
                    ResourceLocation(String id) { this.id = id; }
                    static ResourceLocation tryParse(String id) {
                        return id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") ? new ResourceLocation(id) : null;
                    }
                    ResourceLocation location() { return this; }
                    public boolean equals(Object o) { return o instanceof ResourceLocation r && id.equals(r.id); }
                    public int hashCode() { return id.hashCode(); }
                    public String toString() { return id; }
                }
                static class ServerLevel {
                    ResourceLocation dimension() { return new ResourceLocation("minecraft:overworld"); }
                }
                record Holder(String id) { }
                static class Registry {
                    Holder get(ResourceLocation key) {
                        registry++;
                        if (scenario.equals("LOOKUP_THROW")) throw new IllegalStateException("registry");
                        return scenario.equals("MISSING") ? null : new Holder(key.toString());
                    }
                }
                static class Progress {
                    boolean isDone() {
                        if (scenario.equals("IS_DONE_THROW")) throw new IllegalStateException("progress");
                        return scenario.equals("MATCHED");
                    }
                }
                static class PlayerAdvancements {
                    final Map<String, Progress> entries = new HashMap<>();
                    Progress getOrStartProgress(Holder holder) {
                        progress++;
                        if (scenario.equals("PROGRESS_THROW")) throw new IllegalStateException("progress");
                        return entries.computeIfAbsent(holder.id(), id -> { initialized++; return new Progress(); });
                    }
                }
                static class Server {
                    final Registry registry = new Registry();
                    boolean isSameThread() { return !scenario.equals("OFF_THREAD"); }
                    Registry getAdvancements() {
                        if (scenario.equals("REGISTRY_THROW")) throw new IllegalStateException("registry");
                        return scenario.equals("REGISTRY_NULL") ? null : registry;
                    }
                }
                static class NumenPlayer {
                    final Server server = new Server();
                    final PlayerAdvancements advancements = new PlayerAdvancements();
                    Server getServer() {
                        if (scenario.equals("SERVER_THROW")) throw new IllegalStateException("server");
                        return scenario.equals("SERVER_NULL") ? null : server;
                    }
                    ServerLevel serverLevel() { return scenario.equals("LEVEL_NULL") ? null : new ServerLevel(); }
                    PlayerAdvancements getAdvancements() { return advancements; }
                    UUID getUUID() { return new UUID(0, 1); }
                }
                static class RddPlugin {
                    static boolean decomposing(UUID id) { return false; }
                    static RddRuntime runtime(UUID id) { return runtime; }
                }
                static class RddDetector {
                    static Map<String,Integer> countInventory(NumenPlayer p) { return Map.of(); }
                    static boolean conditionMatches(NumenPlayer p, Subtask s, Map<String,Integer> inventory) {
                        detector++;
                        if (!s.condition().get("type").equals("y_below")) throw new AssertionError("wrong path");
                        return ordinary;
                    }
                }
            """;

    // These are deliberately seam counters, not copies of non-advancement production logic.
    private static final String WORLD_SEAMS = """
                    static List<String> unresolvedTags(NumenPlayer p, Map<String,Object> c) {
                        unresolved++;
                        return scenario.equals("UNKNOWN_TAG") ? List.of("#test:missing") : List.of();
                    }
                    static boolean matches(NumenPlayer p, Map<String,Object> c) { worldMatches++; return ordinary; }
            """;

    private static final String HOST_TAIL = """
                public static void reset(String next, boolean other) {
                    scenario = next; ordinary = other; registry = progress = initialized = unresolved = worldMatches = detector = 0;
                    player = new NumenPlayer(); runtime = null;
                }
                @SuppressWarnings("unchecked")
                static Map<String,Object> condition(String json) { return json == null ? null : new Gson().fromJson(json, Map.class); }
                public static String verify(String json) { return RddWorldFacts.advancementResult(player, condition(json)).name(); }
                public static String probe(String json) {
                    JsonObject args = new JsonObject(); args.addProperty("condition", json);
                    String[] answer = new String[1];
                    new WorldFactProbeTool().onServerCall("test", args, player, value -> answer[0] = value);
                    return answer[0];
                }
                public static String status(String json) {
                    var task = Subtask.hardCoded("task", "test task", condition(json));
                    var primary = new PrimaryGoal("primary", "test primary", List.of(task));
                    var goal = new Goal("goal", "test goal", List.of(primary));
                    runtime = new RddRuntime(new TaskChain(goal), new AssetRegistry());
                    String[] answer = new String[1];
                    new RddStatusTool().onServerCall("test", new JsonObject(), player, value -> answer[0] = value);
                    return answer[0];
                }
                public static String probeDescription() { return new WorldFactProbeTool().description(); }
                public static String statusDescription() { return new RddStatusTool().description(); }
                public static int count(String key) {
                    return switch (key) {
                        case "registry" -> registry; case "progress" -> progress; case "initialized" -> initialized;
                        case "unresolved" -> unresolved; case "worldMatches" -> worldMatches; case "detector" -> detector;
                        default -> throw new IllegalArgumentException(key);
                    };
                }
            }
            """;
}
