package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.google.gson.Gson;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Whole-owner consumer test: compile the CURRENT, unmodified RddFailureRecovery and
 * RddOptionalFood source, not extracted methods or a copied recovery algorithm.
 * Only host IO is substituted: UUID-only NumenPlayer, dispatcher occupancy, plugin
 * flags/body-state/outbound calls, fresh inventory reads, logging/events, three watchers
 * and quality-report delivery. TaskChain, RddRuntime, AssetRegistry, FailureClassifier,
 * RecoveryPolicy, ResourceBudget, RiskGate and optional-food dependencies are real core.
 * Child-first loading prevents installed MC/plugin classes from replacing these seams.
 * This does not test detector tick wiring, actual watcher logic, MC dispatch, persistence,
 * replan acceptance or inventory scanning. Compiler/setup failures are SETUP ERRORs,
 * not skipped tests or evidence of a recovery regression. Static authoring only: no
 * Java/build/test execution was performed when adding this file.
 */
class RddFailureRecoveryConsumerTest {
    private static final String PKG = "com.dwinovo.numen.plugins.rdd.";
    @TempDir static Path compiled;
    private static URLClassLoader loader;
    private static Class<?> harness;

    @BeforeAll static void compileWholeOwner() throws Exception {
        try {
            Path plugin = root().resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
            List<String> sources = new ArrayList<>();
            for (String name : List.of("RddFailureRecovery", "RddOptionalFood")) {
                Path copy = compiled.resolve(name + ".java");
                // Entire source, byte-for-byte: no signature/import/body replacement.
                Files.copy(plugin.resolve(name + ".java"), copy);
                sources.add(copy.toString());
            }
            sources.add(source("RecoveryHarness.java", HARNESS));
            sources.add(source("NumenPlayer.java", """
                    package com.dwinovo.numen.entity;
                    public final class NumenPlayer {
                        private final java.util.UUID id = java.util.UUID.randomUUID();
                        public java.util.UUID getUUID() { return id; }
                    }
                    """));
            sources.add(source("CompanionTickDispatcher.java", """
                    package com.dwinovo.numen.task;
                    public final class CompanionTickDispatcher {
                        public static Object slot;
                        public static Object currentTaskFor(java.util.UUID id) { return slot; }
                    }
                    """));
            var compiler = ToolProvider.getSystemJavaCompiler();
            assertNotNull(compiler, "SETUP ERROR: a full JDK is required for whole-owner compilation");
            List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none",
                    "-classpath", location(TaskChain.class) + File.pathSeparator + location(Gson.class)
                    + File.pathSeparator + System.getProperty("java.class.path"),
                    "-sourcepath", compiled.toString(), "-d", compiled.toString()));
            args.addAll(sources);
            ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
            assertEquals(0, compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new)),
                    () -> "SETUP ERROR: complete current owner/seams failed to compile:\n"
                            + diagnostics.toString(StandardCharsets.UTF_8));
            loader = new URLClassLoader(new URL[]{compiled.toUri().toURL()},
                    RddFailureRecoveryConsumerTest.class.getClassLoader()) {
                @Override protected synchronized Class<?> loadClass(String name, boolean resolve)
                        throws ClassNotFoundException {
                    if (name.startsWith(PKG)
                            || name.equals("com.dwinovo.numen.entity.NumenPlayer")
                            || name.equals("com.dwinovo.numen.task.CompanionTickDispatcher")) {
                        Class<?> type = findLoadedClass(name);
                        if (type == null) type = findClass(name); // No platform fallback for a missing seam.
                        if (resolve) resolveClass(type);
                        return type;
                    }
                    return super.loadClass(name, resolve);
                }
            };
            harness = loader.loadClass(PKG + "RecoveryHarness");
            for (String name : List.of("RddFailureRecovery", "RddOptionalFood", "RddPlugin")) {
                assertEquals(compiled.toUri().toURL(), loader.loadClass(PKG + name)
                        .getProtectionDomain().getCodeSource().getLocation(), "SETUP ERROR: wrong owner/seam loaded");
            }
            assertSame(TaskChain.class, loader.loadClass(TaskChain.class.getName()),
                    "SETUP ERROR: real core must retain its class identity");
        } catch (Exception | LinkageError ex) {
            throw new AssertionError("SETUP ERROR: whole-owner source harness unavailable", ex);
        }
    }

    @AfterAll static void closeLoader() throws Exception { if (loader != null) loader.close(); }

    @Test void levelTwoRetriesTwiceThenDiagnosesOnceAndKeepsWatching() throws Exception {
        Fixture f = fixture(false);
        for (int n = 1; n <= 2; n++) {
            f.fail(); f.call("failure");
            assertEquals(SubtaskStatus.RUNNING, f.chain.currentSubtaskStatus());
            assertEquals(n, f.events("monitor:subtask_retry").size());
            assertEquals(2, f.events("monitor:subtask_retry").get(n - 1).get("max"));
        }
        f.fail(); f.call("failure");
        assertEquals(SubtaskStatus.FAILED, f.chain.currentSubtaskStatus());
        assertEquals(1, f.events("instrument:loop_detected").size());
        Map<String, Object> parked = f.events("monitor:subtask_parked").get(0);
        assertEquals("UNKNOWN", parked.get("failureClass"));
        assertEquals("REPLAN", parked.get("recovery"));
        assertEquals("REQUEST_REPLAN", parked.get("action"));
        assertEquals(false, parked.get("auto"));
        assertEquals(1, f.count("replan"));
        assertEquals(1, f.count("quality"));
        assertEquals(3, f.count("inventory"), "canSkip, diagnostic context, classifier each read fresh");
        f.call("failure");
        assertEquals(1, f.events("instrument:loop_detected").size());
        assertEquals(1, f.count("replan"));
        assertEquals(3, f.count("nudge"), "two retries plus one diagnostic, not repeated nudges");
        assertEquals(2, f.count("parked.watch"));
        assertEquals(4, f.count("inventory"), "repeated parking only rechecks optional eligibility");
    }

    @Test void bodyBudgetIsThreeSubmissionsNotTheLevelTwoBudget() throws Exception {
        Fixture f = fixture(false);
        f.set("body", 1);
        f.call("bodyFailure");
        assertEquals(2, f.call("bodyCount"));
        assertEquals(SubtaskStatus.RUNNING, f.chain.currentSubtaskStatus());
        f.call("bodyFailure");
        assertEquals(3, f.call("bodyCount"));
        assertEquals(2, f.count("resubmit"));
        for (Map<String, Object> event : f.events("monitor:subtask_retry")) assertEquals(3, event.get("max"));
        f.call("bodyFailure");
        assertEquals(SubtaskStatus.FAILED, f.chain.currentSubtaskStatus());
        assertEquals(-1, f.call("bodyCount"));
        assertEquals(2, f.count("resubmit"));
        assertEquals(1, f.events("monitor:subtask_failed").size());
        f.call("bodyFailure");
        assertEquals(1, f.events("instrument:loop_detected").size(), "cleared body prevents repeat failure");
    }

    @Test void staleBodyIdAndOccupiedDispatcherDoNotConsumeBodyBudget() throws Exception {
        Fixture f = fixture(false);
        f.set("staleBody", 1); f.call("bodyFailure");
        assertEquals(1, f.call("bodyCount"));
        assertEquals(List.of(), f.trace());
        f.set("body", 1); f.set("busy", true); f.call("bodyFailure");
        assertEquals(1, f.call("bodyCount"));
        assertEquals(List.of(), f.trace());
        assertEquals(SubtaskStatus.RUNNING, f.chain.currentSubtaskStatus());
    }

    @Test void differentCurrentSubtaskDoesNotInheritPreviousRetryCap() throws Exception {
        Fixture f = fixture(false);
        f.call("rejectDeath"); // cap and parked identity belong to s0
        f.rt.skipSubtask("s0", "fixture advances to a new current id");
        f.rt.startCurrent(); f.fail(); f.call("failure");
        assertEquals("s1", f.chain.currentSubtask().id());
        assertEquals(SubtaskStatus.RUNNING, f.chain.currentSubtaskStatus());
        assertEquals(1, f.events("monitor:subtask_retry").get(0).get("retry"));
        assertEquals(0, f.count("replan"));
    }

    @Test void supervisionPauseKeepsFailedChainAndClearsOnlyMatchingIdleBody() throws Exception {
        Fixture f = fixture(false);
        f.set("supervision", false); f.fail(); f.call("failure");
        assertEquals(SubtaskStatus.FAILED, f.chain.currentSubtaskStatus());
        assertEquals(List.of(), f.trace());
        f.chain.retrySubtask("s0"); f.rt.startCurrent();
        f.set("body", 1); f.call("bodyFailure");
        assertEquals(SubtaskStatus.RUNNING, f.chain.currentSubtaskStatus());
        assertEquals(-1, f.call("bodyCount"));
        assertEquals(List.of("clearBody"), f.trace());
        f.set("supervision", true); f.fail(); f.call("failure");
        assertEquals(1, f.events("monitor:subtask_retry").get(0).get("retry"), "pause spent no retry");
    }

    @Test void rejectedDeathCapsAndParksWithoutRepeatedAutomaticDiagnosis() throws Exception {
        Fixture f = fixture(false);
        f.set("body", 2); f.call("rejectDeath");
        assertEquals(SubtaskStatus.FAILED, f.chain.currentSubtaskStatus());
        assertEquals(-1, f.call("bodyCount"));
        assertEquals(List.of("clearBody", "parked.watch", "save", "snapshot:death_replan_rejected_parked"), f.trace());
        f.call("failure"); f.call("failure");
        assertEquals(SubtaskStatus.FAILED, f.chain.currentSubtaskStatus(), "cap blocks retry/start");
        assertEquals(0, f.events("monitor:subtask_retry").size());
        assertEquals(0, f.events("instrument:loop_detected").size(), "parked identity blocks diagnosis");
        assertEquals(0, f.count("nudge"));
        assertEquals(0, f.count("replan"));
        assertEquals(0, f.count("quality"));
        assertEquals(3, f.count("parked.watch"));
    }

    @Test void optionalSkipUsesThreeFreshReadsAndPreservesCleanupOrder() throws Exception {
        Fixture f = fixture(true);
        for (int n = 0; n < 2; n++) { f.fail(); f.call("failure"); }
        f.fail(); f.call("clearTrace");
        // First read is empty; second covers food; third is independent diagnostic context.
        f.call("optionalSamples"); f.call("failure");
        assertEquals(SubtaskStatus.SKIPPED, f.chain.subtaskStatuses().get("s0"));
        assertEquals("s1", f.chain.currentSubtask().id());
        assertEquals(List.of("inventory", "inventory", "inventory", "instrument:repeat_gather",
                "clearBody", "parked.clear", "stall.clear", "furnace.remove", "finish:s1:SKIPPED",
                "monitor:subtask_skipped", "snapshot:optional_food_skipped"), f.trace());
        Map<?, ?> context = (Map<?, ?>) f.events("instrument:repeat_gather").get(0).get("context");
        assertEquals(Map.of("minecraft:bread", 99), context.get("lastObservedInventory"));
        assertEquals(0, f.count("replan"));
        assertEquals(0, f.count("parked.watch"));
    }

    private static Fixture fixture(boolean optional) throws Exception { return new Fixture(optional); }
    private static final class Fixture {
        final TaskChain chain;
        final RddRuntime rt;
        final Object host;
        Fixture(boolean optional) throws Exception {
            Subtask first = Subtask.hardCoded("s0", "collect supplies",
                    Map.of("asset_key", optional ? "minecraft:bread" : "minecraft:iron_ingot",
                            "minimum", 4, "optional", optional), new BodyInstruction("collect_items", Map.of()));
            chain = new TaskChain(new Goal("g", "test goal", List.of(new PrimaryGoal("p", "test phase",
                    List.of(first, Subtask.hardCoded("s1", "next step", Map.of("asset_key", "minecraft:stone", "minimum", 1)))))));
            rt = new RddRuntime(chain, new AssetRegistry());
            rt.startCurrent();
            host = harness.getConstructor(RddRuntime.class).newInstance(rt);
        }
        void fail() { chain.markFailed(chain.currentSubtask().id(), "fixture unmet condition"); }
        Object call(String name) throws Exception { return invoke(name, new Class<?>[0]); }
        void set(String name, Object value) throws Exception {
            invoke("set", new Class<?>[]{String.class, Object.class}, name, value);
        }
        Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
            try { return harness.getMethod(name, types).invoke(host, args); }
            catch (InvocationTargetException ex) {
                if (ex.getCause() instanceof Error error) throw error;
                if (ex.getCause() instanceof Exception exception) throw exception;
                throw ex;
            }
        }
        @SuppressWarnings("unchecked") List<String> trace() throws Exception { return (List<String>) call("trace"); }
        int count(String name) throws Exception { return (int) trace().stream().filter(name::equals).count(); }
        @SuppressWarnings("unchecked") List<Map<String, Object>> events(String name) throws Exception {
            return (List<Map<String, Object>>) invoke("events", new Class<?>[]{String.class}, name);
        }
    }

    private static String source(String name, String content) throws Exception {
        Path file = compiled.resolve(name); Files.writeString(file, content); return file.toString();
    }
    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
    private static Path root() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddFailureRecovery.java"))) return p;
        }
        throw new AssertionError("SETUP ERROR: cannot locate current RddFailureRecovery source");
    }

    private static final String HARNESS = """
            package com.dwinovo.numen.plugins.rdd;
            import com.dwinovo.numen.entity.NumenPlayer;
            import com.dwinovo.numen.task.CompanionTickDispatcher;
            import com.dwinovo.numen.rdd.api.*;
            import com.dwinovo.numen.rdd.core.RddRuntime;
            import com.dwinovo.numen.rdd.fail.TaskQualityReport;
            import java.util.*;

            public final class RecoveryHarness {
                static final List<String> calls = new ArrayList<>();
                static final Map<String,List<Map<String,Object>>> emitted = new HashMap<>();
                final RddRuntime rt;
                final NumenPlayer ap = new NumenPlayer();
                final RddFailureRecovery owner = new RddFailureRecovery(
                        new RddFurnaceWatch(), new RddStallWatcher(), new RddParkedWatcher());
                public RecoveryHarness(RddRuntime rt) {
                    this.rt=rt; calls.clear(); emitted.clear();
                    RddPlugin.enabled=true; RddPlugin.submission=true; RddPlugin.state=null;
                    CompanionTickDispatcher.slot=null; RddDetector.samples.clear();
                }
                static void event(String name, Map<String,?> data) {
                    calls.add(name);
                    emitted.computeIfAbsent(name, k -> new ArrayList<>()).add(new LinkedHashMap<>(data));
                }
                public void failure() { owner.handleSubtaskFailure(ap,rt,rt.chain().currentSubtask()); }
                public void bodyFailure() { owner.maybeRetryOrFail(ap,rt,rt.chain().currentSubtask()); }
                public void rejectDeath() { owner.parkRejectedDeathReplan(ap,rt); }
                public int bodyCount() { return RddPlugin.state==null ? -1 : RddPlugin.state.submitCount(); }
                public void set(String key,Object value) {
                    switch(key) {
                        case "supervision" -> RddPlugin.enabled=(Boolean)value;
                        case "busy" -> CompanionTickDispatcher.slot=(Boolean)value ? new Object() : null;
                        case "body" -> RddPlugin.state=new RddPlugin.BodyState(rt.chain().currentSubtask().id(),(Integer)value);
                        case "staleBody" -> RddPlugin.state=new RddPlugin.BodyState("obsolete-id",(Integer)value);
                        default -> throw new IllegalArgumentException(key);
                    }
                }
                public List<String> trace() { return List.copyOf(calls); }
                public List<Map<String,Object>> events(String name) { return List.copyOf(emitted.getOrDefault(name,List.of())); }
                public void clearTrace() { calls.clear(); emitted.clear(); }
                public void optionalSamples() {
                    RddDetector.samples.add(Map.of());
                    RddDetector.samples.add(Map.of("minecraft:bread",4));
                    RddDetector.samples.add(Map.of("minecraft:bread",99));
                }
            }
            final class RddPlugin {
                static boolean enabled,submission;
                record BodyState(String subtaskId,int submitCount) {}
                static BodyState state;
                static boolean supervisionEnabled() { return enabled; }
                static boolean bodySubmissionEnabled() { return submission; }
                static BodyState bodyState(UUID id) { return state; }
                static void clearBody(UUID id) { RecoveryHarness.calls.add("clearBody"); state=null; }
                static void rememberBody(UUID id,String task,int n) { state=new BodyState(task,n); }
                static void saveRuntimes() { RecoveryHarness.calls.add("save"); }
                static void publishTaskSnapshot(UUID id,String reason) { RecoveryHarness.calls.add("snapshot:"+reason); }
                static void nudge(UUID id,String message) { RecoveryHarness.event("nudge",Map.of("message",message)); }
                static boolean requestReplan(UUID id,String reason) { RecoveryHarness.calls.add("replan"); return false; }
                static void finishResolvedPrimary(UUID id,RddRuntime rt) {
                    RecoveryHarness.calls.add("finish:"+rt.chain().currentSubtask().id()+":"+rt.chain().subtaskStatuses().get("s0"));
                }
            }
            final class RddDetector {
                static final Logger LOG=new Logger();
                static final Deque<Map<String,Integer>> samples=new ArrayDeque<>();
                static Map<String,Integer> countInventory(NumenPlayer ap) {
                    RecoveryHarness.calls.add("inventory");
                    return samples.isEmpty() ? Map.of() : samples.removeFirst();
                }
                static final class Logger {
                    void info(String message,Object... args) {}
                    void warn(String message,Object... args) {}
                }
            }
            final class RddFurnaceWatch { void remove(UUID id) { RecoveryHarness.calls.add("furnace.remove"); } }
            final class RddStallWatcher { void clear(UUID id) { RecoveryHarness.calls.add("stall.clear"); } }
            final class RddParkedWatcher {
                void clear(UUID id) { RecoveryHarness.calls.add("parked.clear"); }
                void watch(NumenPlayer ap,RddRuntime rt,Subtask current) { RecoveryHarness.calls.add("parked.watch"); }
            }
            final class RddBodyDispatcher {
                static void resubmit(NumenPlayer ap,Subtask task) { RecoveryHarness.calls.add("resubmit"); }
            }
            final class RddInstrumentation {
                static final String LOOP_DETECTED="loop_detected", REPEAT_GATHER="repeat_gather";
                static void publish(String type,Map<String,?> data) { RecoveryHarness.event("instrument:"+type,data); }
            }
            final class RddMonitor {
                static void publish(String type,Map<String,?> data) { RecoveryHarness.event("monitor:"+type,data); }
            }
            final class RddTaskQualityReporter {
                static void report(NumenPlayer ap,Subtask task,TaskQualityReport.Reason reason,int count,String note,String action) {
                    RecoveryHarness.event("quality",Map.of("reason",reason.name(),"count",count,"action",action));
                }
            }
            """;
}
