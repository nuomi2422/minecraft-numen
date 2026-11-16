package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.*;
import com.dwinovo.numen.rdd.side.*;
import com.dwinovo.numen.rdd.side.death.*;
import com.google.gson.Gson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-16: restored PENDING mainline -> drop-only terminal -> actual host replan consumer.
 * Parent-provided evidence: rdd.jsonl:1114-1125, session80640ed6/server58020cbe, death50,
 * PENDING r3/v17 -> confirmed loss -> replanRequested=true -> old r3-0 kickoff ACTIVE/v18.
 * WHY not startCurrent first: that bypasses the broken PENDING admission window.
 * Like AdvancementTickWiringRegressionTest, compile current source excerpts, not copied rules.
 * Run this exact class only through the parent's managed plugins:rdd TestOnly slot.
 * MC IO, observation, risk permission and post-admission LLM dispatch are bounded shell seams;
 * this does not authenticate a live world or test the loss-impact analyzer/planner callback.
 */
class RddPendingDeathSettlementReplanTest {
    private static final String PACKAGE = "com.dwinovo.numen.plugins.rdd.";
    @TempDir static Path output;
    private static URLClassLoader loader;
    private static Class<?> host;

    @BeforeAll static void compileCurrentConsumers() throws Exception {
        Path plugin = root().resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
        String source = Files.readString(plugin.resolve("RddPlugin.java")).replace("\r\n", "\n");
        String request = method(source, "    private static boolean requestReplan(UUID companionId, String reason, RddReplanTransition.Trigger trigger,\n"
                + "                                         String soldierHint, boolean skipRecoverySideTask,\n"
                + "                                         Runnable acceptedSideEffect,");
        // Stop at the real admission/LLM boundary; keep all budget, guard and accepted-side-effect code.
        request = before(request, "        try {\n            long ownerRevision = chain.planRevision();")
                + "        plannerRequests++; trace.add(\"planner_request\"); return true;\n    }\n";
        String detector = Files.readString(plugin.resolve("RddDetector.java")).replace("\r\n", "\n");
        String admission = between(detector,
                "            // 刚推进到新的当前一级(PENDING/WAITING)：",
                "            SubtaskStatus status = chain.currentSubtaskStatus();");
        String generated = HEAD
                + method(source, "    static boolean onDeathRecoverySettled(")
                + method(source, "    private static boolean requestReplan(UUID companionId, String reason, boolean fromNegotiation,")
                + method(source, "    private static boolean requestReplan(UUID companionId, String reason, RddReplanTransition.Trigger trigger,\n"
                    + "                                         String soldierHint, boolean skipRecoverySideTask)")
                + method(source, "    private static boolean requestReplan(UUID companionId, String reason, RddReplanTransition.Trigger trigger,\n"
                    + "                                         String soldierHint, boolean skipRecoverySideTask,\n"
                    + "                                         Runnable acceptedSideEffect)")
                + request
                + "public static void detectorAdmission(UUID id) { var ap = new Body(id); var rt = runtime(id); var chain = rt.chain(); var ps = chain.primaryStatus();\n"
                + admission + " trace.add(\"kickoff_boundary\"); }\n"
                + "static class SideTaskHost {\n"
                + method(Files.readString(plugin.resolve("SideTaskHost.java")).replace("\r\n", "\n"), "    private static void publish(")
                + "static SideTaskWorldPort.PreparationFacts deathPreparationResult(UUID id, String payload) { throw new AssertionError(\"drop-only must not read preparation\"); }\n}\n"
                + TAIL;
        Path java = output.resolve("RddPlugin.java");
        Files.writeString(java, generated, StandardCharsets.UTF_8);
        Path dispatcher = output.resolve("CompanionTickDispatcher.java");
        Files.writeString(dispatcher, "package com.dwinovo.numen.task; public class CompanionTickDispatcher { "
                + "public static void stopActive(Object body, String reason) { throw new AssertionError(\"drop-only has no body stop\"); } }", StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-classpath",
                location(TaskChain.class) + File.pathSeparator + location(Gson.class) + File.pathSeparator
                        + System.getProperty("java.class.path"), "-d", output.toString(), java.toString(), dispatcher.toString()));
        // Compile the pure host dependencies unchanged, alongside the source-excerpt host.
        for (String name : List.of("RddReplanTransition", "RddSurvivalGate", "DeathPreparationHost", "RddDeathLedgerStore"))
            args.add(plugin.resolve(name + ".java").toString());
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK required by existing source-excerpt test pattern");
        assertEquals(0, compiler.run(null, null, null, args.toArray(String[]::new)),
                "current production consumers must compile before any business assertion");
        loader = new URLClassLoader(new URL[]{output.toUri().toURL()}, getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith(PACKAGE)) {
                    Class<?> found = findLoadedClass(name);
                    if (found == null) {
                        try { found = findClass(name); } catch (ClassNotFoundException ignored) { }
                    }
                    if (found != null) { if (resolve) resolveClass(found); return found; }
                }
                return super.loadClass(name, resolve);
            }
        };
        host = loader.loadClass(PACKAGE + "RddPlugin");
        assertEquals(output.toUri().toURL(), host.getProtectionDomain().getCodeSource().getLocation());
    }

    @AfterAll static void closeLoader() throws Exception { if (loader != null) loader.close(); }
    private static ClassLoader getClassLoader() { return RddPendingDeathSettlementReplanTest.class.getClassLoader(); }

    @Test void restoredPendingLossMustBeConsumedOnceAfterAdmissionBeforeOldKickoff(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(dir);
        String pending = f.chain.toJson();
        f.finishDropsAbsent();
        assertEquals(pending, f.chain.toJson(), "failed PENDING request must not half-write mainline or history");
        assertEquals(0, number("plannerRequests"));
        assertTrue(budget().isEmpty(), "refused early request is free");
        assertEquals(PrimaryGoalStatus.PENDING, f.chain.primaryStatus());
        assertEquals(true, event("death_recovery_settled").get("replanRequested"));
        assertEquals(false, f.terminal.get("prepareSettlementAccepted"));
        assertEquals(true, event("side_task_resumed_mainline").get("mainlineResumed"),
                "the actual drop-only engine currently releases without preparation ACK");

        call("detectorAdmission", new Class<?>[]{UUID.class}, f.id);
        // Expected RED on 7f09: 0 requests, old plan is ACTIVE/RUNNING and reaches kickoff_boundary.
        assertAll("confirmed loss must survive PENDING refusal until legal admission, not old kickoff",
                () -> assertEquals(1, number("plannerRequests"), "accepted death replan must reach planner exactly once"),
                () -> assertEquals(PrimaryGoalStatus.REPLANNING, f.chain.primaryStatus(), "do not dispatch unchanged r3-0"),
                () -> assertFalse(trace().contains("kickoff_boundary"), "consume deferred loss before old kickoff boundary"),
                () -> assertEquals(1, budget().values().stream().mapToInt(Integer::intValue).sum(), "only one accepted request spends budget"),
                () -> assertEquals(f.history, f.chain.satisfiedStages(), "completed historical stages remain facts"));
    }

    @Test void activeDropOnlyLossStillRequestsExactlyOnceWithoutRewritingHistory(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(dir);
        // Positive control only: real dependency admission, NOT a shortcut in the PENDING regression.
        assertTrue(f.runtime.activateCurrentFromSnapshot(PlanningAssetSnapshot.from(Map.of(), f.runtime.assets())));
        f.finishDropsAbsent();
        assertEquals(1, number("plannerRequests"));
        assertEquals(PrimaryGoalStatus.REPLANNING, f.chain.primaryStatus());
        assertEquals(1, budget().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(f.history, f.chain.satisfiedStages());
        assertEquals(3, f.chain.planRevision(), "request alone does not install a new plan");
    }

    @Test void duplicateOldSettlementAfterPendingRefusalCannotHalfWriteOrSpend(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(dir);
        f.finishDropsAbsent();
        String chain = f.chain.toJson();
        var ledger = RddDeathLedger.snapshot(f.id);
        int events = trace().size();
        assertFalse((Boolean) call("onDeathRecoverySettled", new Class<?>[]{UUID.class, Map.class}, f.id, f.terminal));
        assertEquals(chain, f.chain.toJson());
        assertEquals(ledger, RddDeathLedger.snapshot(f.id));
        assertEquals(events, trace().size(), "duplicate old terminal is not a new settlement/planner request");
        assertEquals(0, number("plannerRequests"));
        assertTrue(budget().isEmpty());
        assertEquals(f.history, f.chain.satisfiedStages());
    }

    private static final class Fixture implements SideTaskWorldPort {
        final UUID id = UUID.randomUUID();
        final TaskChain chain;
        final RddRuntime runtime;
        final Set<String> history;
        final SideTaskEngine engine;
        DeathDropState drops = DeathDropState.UNKNOWN;
        Map<String, Object> terminal;

        Fixture(Path dir) throws Exception {
            TaskChain initial = new TaskChain(new Goal("g", "finish trade", List.of(
                    new PrimaryGoal("prior", "verified shelter", List.of(Subtask.hardCoded("prior-0", "shelter", Map.of("minimum", 1)))),
                    new PrimaryGoal("trade", "collect emeralds", List.of(Subtask.hardCoded("trade-r3-0", "old emerald plan",
                            Map.of("asset_key", "minecraft:emerald", "minimum", 20)))))));
            initial.settlePrimaryByFact("prior", "bounded fixture world fact", true);
            for (int rev = 0; rev < 3; rev++) initial.nextPlanRevision();
            Path saved = dir.resolve("chain.json");
            Files.writeString(saved, initial.toJson(), StandardCharsets.UTF_8);
            chain = TaskChain.fromJson(Files.readString(saved, StandardCharsets.UTF_8));
            assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
            assertEquals(SubtaskStatus.PENDING, chain.currentSubtaskStatus());
            assertEquals(3, chain.planRevision());
            history = Set.copyOf(chain.satisfiedStages());
            assertFalse(history.isEmpty());
            runtime = new RddRuntime(chain, new AssetRegistry());
            call("reset", new Class<?>[]{UUID.class, RddRuntime.class, Path.class}, id, runtime, dir);

            // Real store save/load and core restore preserve seq50 rather than creating fifty fake deaths.
            var death = new RddDeathLedger.Death(50, 900, 1000, "minecraft:overworld 0 64 0", 1,
                    RddDeathLedger.State.UNVERIFIED, "", Map.of("minecraft:emerald", 12));
            RddDeathLedger.restore(id, new RddDeathLedger.LedgerSnapshot(51, List.of(death)));
            call("saveLedger", new Class<?>[]{UUID.class}, id);
            RddDeathLedger.clear(id);
            call("ensureLedgerLoaded", new Class<?>[]{UUID.class}, id);
            assertEquals(50, RddDeathLedger.latest(id).seq());
            assertEquals(51, RddDeathLedger.snapshot(id).nextSeq());

            SideTaskEngine original = engine();
            assertTrue(original.startNow(new DeathRecoverySideTaskType(), id, this, token(), Map.of("death_seq", "50")));
            assertTrue(DeathPreparation.choose(original, id, DeathRecoveryDecision.RECOVER_DROPS_FIRST));
            original.tick(id, this, token(), false);
            assertEquals(DeathRecoverySideTaskType.PHASE_RECOVER, original.current(id).orElseThrow().phase());
            String side = original.toJson();
            engine = engine();
            engine.restore(side);
            assertEquals(DeathRecoverySideTaskType.PHASE_RECOVER, engine.current(id).orElseThrow().phase());
            assertFalse(engine.current(id).orElseThrow().data().containsKey("prepare_required"));
            trace().clear();
        }

        SideTaskEngine engine() {
            var result = new SideTaskEngine((event, data) -> {
                try {
                    call("sideEvent", new Class<?>[]{String.class, Map.class}, event, data);
                    if (event.equals("side_task_skipped")) terminal = new LinkedHashMap<>(data);
                } catch (Exception ex) { throw new AssertionError(ex); }
            }, token -> token);
            result.registerType(new DeathRecoverySideTaskType());
            return result;
        }
        MainlineResumeToken token() {
            return new MainlineResumeToken(id, chain.chainInstanceId(), chain.goal().id(), chain.currentPrimary().id(),
                    chain.currentSubtask().id(), chain.planRevision(), 1);
        }
        void finishDropsAbsent() {
            drops = DeathDropState.DROPS_ABSENT;
            engine.tick(id, this, token(), false);
            assertFalse(engine.isActive(id));
            assertNotNull(terminal);
            assertFalse(terminal.containsKey("prepareResume"), "this is the actual drop-only route");
            assertEquals("drops_absent_with_world_evidence", terminal.get("deathRecoveryTerminalReason"));
            assertEquals(RddDeathLedger.State.CONFIRMED_LOST, RddDeathLedger.latest(id).state());
        }
        public long gameTime() { return 1000; }
        public long dayTime() { return 1000; }
        public long gameDay() { return 0; }
        public boolean hasDayNightCycle() { return true; }
        public String worldId() { return "minecraft:overworld"; }
        public boolean isSleeping(UUID id) { return false; }
        public boolean isDay() { return true; }
        public Wake pollWake(UUID id) { return Wake.NONE; }
        public DeathRecoveryFacts deathRecoveryFacts(UUID id, SideTask task) {
            assertEquals(this.id, id); assertEquals("50", task.data().get("death_seq"));
            return new DeathRecoveryFacts(drops, false, false, true, "bounded loaded-chunk drop evidence");
        }
    }

    @SuppressWarnings("unchecked") private static Map<String, Integer> budget() throws Exception { return (Map<String, Integer>) field("REPLAN_COUNTS"); }
    @SuppressWarnings("unchecked") private static List<String> trace() throws Exception { return (List<String>) field("trace"); }
    @SuppressWarnings("unchecked") private static Map<String, Object> event(String name) throws Exception {
        return ((Map<String, Map<String, Object>>) field("events")).get(name);
    }
    private static int number(String name) throws Exception { return ((Number) field(name)).intValue(); }
    private static Object field(String name) throws Exception { var f = host.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
    private static Object call(String name, Class<?>[] types, Object... args) throws Exception {
        var m = host.getDeclaredMethod(name, types); m.setAccessible(true);
        try { return m.invoke(null, args); } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Exception cause) throw cause;
            if (ex.getCause() instanceof Error cause) throw cause;
            throw ex;
        }
    }
    private static Path root() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent())
            if (Files.isRegularFile(p.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddPlugin.java"))) return p;
        throw new AssertionError("production source tree not found");
    }
    private static String location(Class<?> c) throws Exception { return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
    private static String before(String source, String marker) {
        int at = source.indexOf(marker); assertTrue(at >= 0, "source boundary missing: " + marker); return source.substring(0, at);
    }
    private static String between(String source, String start, String end) {
        int a = source.indexOf(start), b = source.indexOf(end, a);
        assertTrue(a >= 0 && b > a, "production consumer boundaries missing"); return source.substring(a, b);
    }
    private static String method(String source, String signature) {
        int start = source.indexOf(signature); assertTrue(start >= 0, "production method missing: " + signature);
        // All selected methods end at the next four-space method-closing brace. Nested blocks indent deeper.
        int end = source.indexOf("\n    }", source.indexOf('{', start));
        assertTrue(end > start, "production method end missing"); return source.substring(start, end + 6) + "\n";
    }

    private static final String HEAD = """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*;
            import java.nio.file.Path;
            import com.dwinovo.numen.rdd.api.*;
            import com.dwinovo.numen.rdd.core.*;
            import com.dwinovo.numen.rdd.side.*;
            import com.dwinovo.numen.rdd.side.death.*;
            public class RddPlugin {
                static final int MAX_REPLAN_PER_PRIMARY = 3, MAX_NEGOTIATION_REPLANS_PER_PRIMARY = 3;
                static final Map<UUID,RddRuntime> RUNTIMES = new HashMap<>();
                static final Map<String,Integer> REPLAN_COUNTS = new HashMap<>();
                static final Set<String> DEATH_SETTLEMENTS = new HashSet<>();
                static final List<String> trace = new ArrayList<>();
                static final Map<String,Map<String,Object>> events = new HashMap<>();
                static int plannerRequests; static Path ledgerDir;
                static class Log { void info(String s,Object... a) {} void warn(String s,Object... a) {} }
                static final Log LOG = new Log();
                public static void reset(UUID id,RddRuntime rt,Path dir) {
                    RUNTIMES.clear(); RUNTIMES.put(id,rt); REPLAN_COUNTS.clear(); DEATH_SETTLEMENTS.clear();
                    trace.clear(); events.clear(); plannerRequests=0; ledgerDir=dir;
                }
                public static RddRuntime runtime(UUID id) { return RUNTIMES.get(id); }
                public static void sideEvent(String e,Map<String,Object> d) { SideTaskHost.publish(e,d); }
                static void saveLedger(UUID id) { RddDeathLedgerStore.save(ledgerDir,id,RddDeathLedger.snapshot(id)); }
                static void ensureLedgerLoaded(UUID id) { RddDeathLedger.restore(id,RddDeathLedgerStore.load(ledgerDir,id)); }
                static void warnLedgerStore(String e,Map<String,Object> d) { throw new AssertionError(e+d); }
                static Map<String,Integer> lastInventory(UUID id) { return Map.of(); }
                static Long lastInventoryAtMillis(UUID id) { return 1000L; }
                static PlanningAssetSnapshot planningSnapshot(UUID id) { return PlanningAssetSnapshot.from(Map.of(),runtime(id).assets()); }
                static void saveRuntimes() {}
                static void publishTaskSnapshot(UUID id,String why) { trace.add(why); }
                static class MinecraftServer {}
                static class ServerLifecycleHooks { static MinecraftServer getCurrentServer() { return new MinecraftServer(); } }
                static Object captureObservationToken(MinecraftServer s) { return s; }
                static class BodyState {}
                static BodyState bodyState(UUID id) { return null; }
                static void clearBodyIfSame(UUID id,BodyState body) { trace.add("accepted_clear_body"); }
                static boolean isMissingAssetCause(UUID id,String why) { throw new AssertionError("skip recovery route"); }
                static String describeMissing(UUID id) { return ""; }
                static boolean insertRecoveryNode(UUID id,String what,String why) { throw new AssertionError(); }
            """;

    private static final String TAIL = """
                // Repair analysis is outside this contract: only record the call, never fake a replan/result.
                static class RddRepairDispatch {
                    static void clearDeathRecoveryHandoff(UUID id,int seq) {}
                    static void recomputeAfterDeath(UUID id,Map<String,Integer> c,boolean lost,String phase,int seq) {
                        trace.add("recompute:"+phase+":"+seq);
                    }
                }
                static class RddMonitor {
                    static void publish(String e,Map<String,Object> d,Object... token) {
                        trace.add(e); events.put(e,new LinkedHashMap<>(d));
                    }
                }
                static class RddInstrumentation {
                    static final String LOOP_DETECTED="loop", RECOVERY_FAILED="recovery_failed";
                    static long currentGameTimeTicks() { return 1000; }
                    static boolean recentDeath(UUID id,long t) { return true; }
                    static void publishAt(String e,Map<String,Object> d,long t) { RddMonitor.publish(e,d); }
                }
                static class RddCompanionBody { static Object get(UUID id) { return null; } }
                record Body(UUID id) { UUID getUUID() { return id; } }
                static class RddGoalDriver { static void needExpansion(UUID id) { throw new AssertionError("expanded fixture"); } }
                static void reportExpansionNeeded(UUID id,String p,String description) { throw new AssertionError(); }
                static boolean riskGateAllows(Body b,String description) { return true; }
                static boolean shouldPublishGatedSnapshot(UUID id,String p,String... kind) { return false; }
                static void publishWaitingOnce(Body b,String key,String e,Map<String,Object> d) { RddMonitor.publish(e,d); }
                static void watchParkedIfStuck(Body b,RddRuntime rt,TaskChain c) {}
            }
            """;
}
