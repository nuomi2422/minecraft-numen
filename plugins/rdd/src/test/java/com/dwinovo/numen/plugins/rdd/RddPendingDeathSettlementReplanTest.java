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
        String failed = between(detector, "            if (status == SubtaskStatus.FAILED) {",
                "            if (status != SubtaskStatus.RUNNING) {");
        String generated = HEAD
                + between(source, "    private record DeferredDeath(", "    /** 同伴自绑床位")
                + detectorExcerpt(between(detector, "    private final Map<UUID, RetryState> retries", "    /** 已因"))
                + between(detector, "    private static final int MAX_SUBTASK_RETRIES", "    /** 资产提前触发")
                + method(source, "    private static boolean deferDeathReplan(")
                + method(source, "    static boolean consumeDeferredDeathReplan(")
                + method(source, "    static boolean onDeathRecoverySettled(")
                + method(source, "    private static boolean requestReplan(UUID companionId, String reason, boolean fromNegotiation,")
                + method(source, "    private static boolean requestReplan(UUID companionId, String reason, RddReplanTransition.Trigger trigger,\n"
                    + "                                         String soldierHint, boolean skipRecoverySideTask)")
                + method(source, "    private static boolean requestReplan(UUID companionId, String reason, RddReplanTransition.Trigger trigger,\n"
                    + "                                         String soldierHint, boolean skipRecoverySideTask,\n"
                    + "                                         Runnable acceptedSideEffect)")
                + method(source, "    static boolean requestReplan(UUID companionId, String reason, boolean fromNegotiation, String soldierHint) {")
                + request
                + "public static void detectorAdmission(UUID id) { var ap = new Body(id); var rt = runtime(id); var chain = rt.chain(); var ps = chain.primaryStatus();\n"
                + admission + " SubtaskStatus status = chain.currentSubtaskStatus();\n"
                + failed + " if (status == SubtaskStatus.RUNNING) trace.add(\"kickoff_boundary\"); }\n"
                + detectorExcerpt(method(detector, "    private void parkRejectedDeathReplan("))
                + detectorExcerpt(method(detector, "    private void handleSubtaskFailure("))
                + method(detector, "    private static boolean hasBackupEquipment(")
                + method(detector, "    private static boolean hasBase(")
                + method(detector, "    private static String nudgeForPlan(")
                + method(detector, "    private static com.dwinovo.numen.rdd.fail.TaskQualityReport.Reason failureKindToQuality(")
                + detectorExcerpt(method(detector, "    private int rsSafeCount("))
                + method(source, "    static boolean requestReplan(UUID companionId, String reason) {")
                + "static class SideTaskHost {\n"
                + method(Files.readString(plugin.resolve("SideTaskHost.java")).replace("\r\n", "\n"), "    private static void publish(")
                + "static SideTaskWorldPort.PreparationFacts deathPreparationResult(UUID id, String payload) { throw new AssertionError(\"drop-only must not read preparation\"); }\n}\n"
                + TAIL;
        Path java = output.resolve("RddPlugin.java");
        Files.writeString(java, generated, StandardCharsets.UTF_8);
        Path dispatcher = output.resolve("CompanionTickDispatcher.java");
        Files.writeString(dispatcher, "package com.dwinovo.numen.task; public class CompanionTickDispatcher { "
                + "public static void stopActive(Object body, String reason) { throw new AssertionError(\"drop-only has no body stop\"); } "
                + "public static Object currentTaskFor(java.util.UUID id) { return null; } }", StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-classpath",
                location(TaskChain.class) + File.pathSeparator + location(Gson.class) + File.pathSeparator
                        + System.getProperty("java.class.path"), "-d", output.toString(), java.toString(), dispatcher.toString()));
        // Compile the pure host dependencies unchanged, alongside the source-excerpt host.
        for (String name : List.of("RddReplanTransition", "RddSurvivalGate", "DeathPreparationHost", "RddDeathLedgerStore", "RddOptionalFood"))
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
        assertEquals(false, event("death_recovery_settled").get("replanRequested"), "deferred is not accepted");
        assertEquals(true, event("death_recovery_settled").get("replanDeferred"));
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

    @Test void riskAndDependencyGatesRetainSameDeferredWithoutSpending(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(dir, List.of(new AssetRequirement("minecraft:bread", 2)));
        f.finishDropsAbsent();
        Object pending = deferred().get(f.id);
        setField("riskAllowed", false);
        call("detectorAdmission", new Class<?>[]{UUID.class}, f.id);
        assertEquals(PrimaryGoalStatus.PENDING, f.chain.primaryStatus());
        assertSame(pending, deferred().get(f.id));
        setField("riskAllowed", true);
        call("detectorAdmission", new Class<?>[]{UUID.class}, f.id);
        assertEquals(PrimaryGoalStatus.WAITING, f.chain.primaryStatus());
        assertSame(pending, deferred().get(f.id));
        assertTrue(budget().isEmpty());
        assertEquals(0, number("plannerRequests"));
        assertFalse(trace().contains("kickoff_boundary"));
        setField("observedCounts", Map.of("minecraft:bread", 2));
        call("detectorAdmission", new Class<?>[]{UUID.class}, f.id);
        assertEquals(PrimaryGoalStatus.REPLANNING, f.chain.primaryStatus());
        assertEquals(1, number("plannerRequests"));
        assertTrue(deferred().isEmpty());
    }

    @Test void secondConsumeOfSameOwnerDoesNotRequestOrChargeAgain(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(dir);
        f.finishDropsAbsent();
        call("detectorAdmission", new Class<?>[]{UUID.class}, f.id);
        String after = f.chain.toJson();
        assertFalse(consume(f.id, f.chain));
        assertEquals(after, f.chain.toJson());
        assertEquals(1, number("plannerRequests"));
        assertEquals(1, budget().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test void replacedChainAndOldCallerCannotMutateNewOwnerOrItsHandoff(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(dir);
        f.finishDropsAbsent();
        TaskChain replacement = TaskChain.fromJson(f.chain.toJson()); // even identical persisted IDs are not ownership
        runtimes().put(f.id, new RddRuntime(replacement, new AssetRegistry()));
        assertTrue(replacement.activateCurrentWithSnapshot(PlanningAssetSnapshot.from(Map.of(), new AssetRegistry())));
        String before = replacement.toJson();
        assertFalse(consume(f.id, replacement));
        assertEquals(before, replacement.toJson());
        assertTrue(deferred().isEmpty());
        // A stale old caller must not erase a subsequently deferred handoff for this replacement.
        TaskChain next = TaskChain.fromJson(f.chain.toJson());
        runtimes().put(f.id, new RddRuntime(next, new AssetRegistry()));
        assertTrue((Boolean) call("deferDeathReplan", new Class<?>[]{UUID.class, int.class, String.class}, f.id, 50, "same death new owner"));
        Object pending = deferred().get(f.id);
        assertFalse(consume(f.id, f.chain));
        assertSame(pending, deferred().get(f.id));
        assertEquals(0, number("plannerRequests"));
        assertTrue(budget().isEmpty());
    }

    @Test void changedPlanRevisionDiscardsDeferredWithoutTouchingNewPlan(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(dir);
        f.finishDropsAbsent();
        f.chain.nextPlanRevision();
        assertTrue(f.runtime.activateCurrentFromSnapshot(PlanningAssetSnapshot.from(Map.of(), f.runtime.assets())));
        String before = f.chain.toJson();
        assertFalse(consume(f.id, f.chain));
        assertEquals(before, f.chain.toJson());
        assertTrue(deferred().isEmpty());
        assertTrue(budget().isEmpty());
        assertEquals(0, number("plannerRequests"));
    }

    @Test void exhaustedBudgetParksAndNextFailureTickCannotRunOldPlan(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(dir);
        f.finishDropsAbsent();
        budget().put(f.id + "|trade|nego", 3);
        assertRejectedRemainsParked(f);
        assertEquals(3, budget().get(f.id + "|trade|nego"));
        assertEquals(false, event("death_replan_consumed").get("replanAccepted"));
    }

    @Test void synchronousRequestFailureBeforeTransitionAlsoParks(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(dir);
        f.finishDropsAbsent();
        setField("captureFails", true); // real request throws at token capture, before transition/budget
        assertRejectedRemainsParked(f);
        assertTrue(budget().isEmpty());
        assertEquals(false, event("death_replan_consumed").get("replanAccepted"));
    }

    @Test void ordinaryNegotiationStillCannotAdmitPending(@TempDir Path dir) throws Exception {
        Fixture f = new Fixture(dir);
        String before = f.chain.toJson();
        assertFalse((Boolean) call("requestReplan", new Class<?>[]{UUID.class, String.class, boolean.class, String.class, boolean.class},
                f.id, "ordinary soldier negotiation", true, null, true));
        assertEquals(before, f.chain.toJson());
        assertTrue(budget().isEmpty());
        assertTrue(deferred().isEmpty());
        assertEquals(0, number("plannerRequests"));
    }

    private static void assertRejectedRemainsParked(Fixture f) throws Exception {
        call("detectorAdmission", new Class<?>[]{UUID.class}, f.id);
        assertEquals(SubtaskStatus.FAILED, f.chain.currentSubtaskStatus());
        String parked = f.chain.toJson();
        // This invokes the real FAILED consumer + handleSubtaskFailure, not just assertions about maps.
        call("detectorAdmission", new Class<?>[]{UUID.class}, f.id);
        call("detectorAdmission", new Class<?>[]{UUID.class}, f.id);
        assertEquals(parked, f.chain.toJson(), "retry/start must not reopen the old plan on later ticks");
        assertEquals(0, number("plannerRequests"));
        assertFalse(trace().contains("kickoff_boundary"));
        assertFalse(trace().contains("subtask_retry"));
        assertTrue(deferred().isEmpty(), "a rejected claim is not put back for automatic retry");
    }

    private static boolean consume(UUID id, TaskChain chain) throws Exception {
        return (Boolean) call("consumeDeferredDeathReplan", new Class<?>[]{UUID.class, TaskChain.class, Runnable.class}, id, chain,
                (Runnable) () -> { throw new AssertionError("stale/duplicate must not park an owner"); });
    }
    @SuppressWarnings("unchecked") private static Map<UUID, Object> deferred() throws Exception { return (Map<UUID, Object>) field("DEFERRED_DEATH_REPLANS"); }
    @SuppressWarnings("unchecked") private static Map<UUID, RddRuntime> runtimes() throws Exception { return (Map<UUID, RddRuntime>) field("RUNTIMES"); }
    private static void setField(String name, Object value) throws Exception { var f = host.getDeclaredField(name); f.setAccessible(true); f.set(null, value); }

    private static final class Fixture implements SideTaskWorldPort {
        final UUID id = UUID.randomUUID();
        final TaskChain chain;
        final RddRuntime runtime;
        final Set<String> history;
        final SideTaskEngine engine;
        DeathDropState drops = DeathDropState.UNKNOWN;
        Map<String, Object> terminal;

        Fixture(Path dir) throws Exception { this(dir, List.of()); }
        Fixture(Path dir, List<AssetRequirement> waitFor) throws Exception {
            TaskChain initial = new TaskChain(new Goal("g", "finish trade", List.of(
                    new PrimaryGoal("prior", "verified shelter", List.of(Subtask.hardCoded("prior-0", "shelter", Map.of("minimum", 1)))),
                    new PrimaryGoal("trade", "collect emeralds", List.of(Subtask.hardCoded("trade-r3-0", "old emerald plan",
                            Map.of("asset_key", "minecraft:emerald", "minimum", 20))), waitFor))));
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
    private static String detectorExcerpt(String source) {
        // Only adapt the MC body type and instance storage to the existing static shell; rules stay source-loaded.
        return source.replace("NumenPlayer", "Body").replace("private final ", "private static final ")
                .replace("private void ", "private static void ").replace("private int ", "private static int ");
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
            import java.util.concurrent.ConcurrentHashMap;
            import com.dwinovo.numen.rdd.fail.*;
            import com.dwinovo.numen.rdd.policy.*;
            import com.dwinovo.numen.task.CompanionTickDispatcher;
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
                static boolean riskAllowed, captureFails;
                static Map<String,Integer> observedCounts = Map.of();
                static class Log { void info(String s,Object... a) {} void warn(String s,Object... a) {} }
                static final Log LOG = new Log();
                public static void reset(UUID id,RddRuntime rt,Path dir) {
                    RUNTIMES.clear(); RUNTIMES.put(id,rt); REPLAN_COUNTS.clear(); DEATH_SETTLEMENTS.clear();
                    trace.clear(); events.clear(); plannerRequests=0; ledgerDir=dir;
                    DEFERRED_DEATH_REPLANS.clear(); retries.clear(); gapParked.clear();
                    riskAllowed=true; captureFails=false; observedCounts=Map.of();
                }
                public static RddRuntime runtime(UUID id) { return RUNTIMES.get(id); }
                public static void sideEvent(String e,Map<String,Object> d) { SideTaskHost.publish(e,d); }
                static void saveLedger(UUID id) {
                    try { RddDeathLedgerStore.save(ledgerDir,id,RddDeathLedger.snapshot(id)); }
                    catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
                }
                static void ensureLedgerLoaded(UUID id) { RddDeathLedger.restore(id,RddDeathLedgerStore.load(ledgerDir,id)); }
                static void warnLedgerStore(String e,Map<String,Object> d) { throw new AssertionError(e+d); }
                static Map<String,Integer> lastInventory(UUID id) { return Map.of(); }
                static Long lastInventoryAtMillis(UUID id) { return 1000L; }
                static PlanningAssetSnapshot planningSnapshot(UUID id) { return PlanningAssetSnapshot.from(observedCounts,runtime(id).assets()); }
                static void saveRuntimes() {}
                static boolean supervisionEnabled() { return true; }
                static void clearBody(UUID id) { trace.add("clear_body"); }
                static void nudge(UUID id,String text) { throw new AssertionError("unexpected automatic nudge: "+text); }
                static void finishResolvedPrimary(UUID id,RddRuntime rt) { throw new AssertionError("not optional food"); }
                static Map<String,Integer> countInventory(Body body) { return observedCounts; }
                static boolean conditionMatches(Body body,Subtask task,Map<String,Integer> counts) { return HardCodedEvaluator.matches(task.condition(),counts); }
                static boolean completeSubtask(Body body,RddRuntime rt,Subtask task) { throw new AssertionError("fixture unmet"); }
                static class Watcher {
                    void watch(Body b,RddRuntime rt,Subtask s) { trace.add("parked_watch"); }
                    void clear(UUID id) {} void remove(UUID id) {}
                }
                static final Watcher parkedWatcher=new Watcher(), stallWatcher=new Watcher(), furnaceWatch=new Watcher();
                static class RddTaskQualityReporter { static void report(Object... args) { throw new AssertionError("unexpected second failure route"); } }

                static void publishTaskSnapshot(UUID id,String why) { trace.add(why); }
                static class MinecraftServer {}
                static class ServerLifecycleHooks { static MinecraftServer getCurrentServer() { return new MinecraftServer(); } }
                static Object captureObservationToken(MinecraftServer s) { if (captureFails) throw new IllegalStateException("bounded token capture failure"); return s; }
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
                    static final String LOOP_DETECTED="loop", RECOVERY_FAILED="recovery_failed", REPEAT_GATHER="repeat_gather";
                    static void publish(String e,Map<String,Object> d) { RddMonitor.publish(e,d); }
                    static long currentGameTimeTicks() { return 1000; }
                    static boolean recentDeath(UUID id,long t) { return true; }
                    static void publishAt(String e,Map<String,Object> d,long t) { RddMonitor.publish(e,d); }
                }
                static class RddCompanionBody { static Object get(UUID id) { return null; } }
                record Body(UUID id) { UUID getUUID() { return id; } }
                static class RddGoalDriver { static void needExpansion(UUID id) { throw new AssertionError("expanded fixture"); } }
                static void reportExpansionNeeded(UUID id,String p,String description) { throw new AssertionError(); }
                static boolean riskGateAllows(Body b,String description) { return riskAllowed; }
                static boolean shouldPublishGatedSnapshot(UUID id,String p,String... kind) { return false; }
                static void publishWaitingOnce(Body b,String key,String e,Map<String,Object> d) { RddMonitor.publish(e,d); }
                static void watchParkedIfStuck(Body b,RddRuntime rt,TaskChain c) {}
            }
            """;
}
