package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Tool -> real admission -> captured Stage-B callback -> queued onServer/guard -> chain -> detector.
 * No MC, real LLM, natural tick loop or IO. The old attempt must not directly pause the restarted one;
 * disposing/reviewing the underlying objection is a separate contract, not proven by a RUNNING state. */
public class RddConcernAsyncCallbackConsumerTest {
    // Public bridge; only this interface (and pure core/Gson) is shared between the two loaders.
    public interface Driver {
        String report(String kind, String subtask, String reason, String suggestion);
        int plannerCalls();
        int negotiationBudgetUsed();
        void complete(List<SubtaskSpec> specs);
        int queued();
        void runNext();
        void negotiate();
        String pendingReason();
        Map<String,Object> review();
        String requestReview(String ref, String evidence);
        String reviewPrompt();
        void completeReview(String json);
        void exhaustBudget();
        void invalidateForDeath();
        void planningTimeout();
        int bodyClears();
        List<String> snapshots();
        List<String> reasons(String event);
    }

    private static final String PKG = "com.dwinovo.numen.plugins.rdd.";
    private static final String HOST = PKG + "ConcernAsyncHost";
    @TempDir static Path out;
    private URLClassLoader loader;
    private RddRuntime rt;
    private TaskChain chain;
    private Driver host;
    private int revision, attempt;
    private static final String OLD = "old attempt: iron out of reach";
    private static final String FRESH = "fresh observation: path flooded";

    @BeforeAll static void compileProductionPath() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("plugins/rdd/build.gradle"))) root = root.getParent();
        assertNotNull(root, "SETUP: could not locate rdd sources");
        Path src = root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
        String plugin = source(src, "RddPlugin"), detector = source(src, "RddDetector");
        String from = "    static boolean requestReplan(UUID companionId, String reason, boolean fromNegotiation, String soldierHint) {";
        String last = "    private static boolean requestReplan(UUID companionId, String reason, RddReplanTransition.Trigger trigger,\n"
                + "                                         String soldierHint, boolean skipRecoverySideTask,\n"
                + "                                         Runnable acceptedSideEffect,";
        String lastReview = "    private static boolean requestReplan(UUID companionId, String reason, RddReplanTransition.Trigger trigger,\n"
                + "                                         String soldierHint, boolean skipRecoverySideTask, Runnable acceptedSideEffect,";
        String request = plugin.substring(start(plugin, from), start(plugin, last)) + method(plugin, last)
                + "\n" + method(plugin, lastReview)
                + "\n" + method(plugin, "    static boolean requestConcernReview(");
        String host = Files.readString(root.resolve("plugins/rdd/src/test/resources/concern-async/ConcernAsyncHost.java.in"))
                .replace("// @DETECTOR@", method(detector,"    private static boolean isOwnerPause(")
                        + "\n" + method(detector,"    private boolean tickNegotiation("))
                .replace("// @LIMITS@", declaration(plugin,"    private static final int MAX_REPLAN_PER_PRIMARY =")
                        + "\n" + declaration(plugin,"    private static final int MAX_NEGOTIATION_REPLANS_PER_PRIMARY ="))
                .replace("// @REQUEST@", request)
                .replace("// @REVISION@", method(plugin,"    private static int nextPlanRevision(")
                        + "\n" + methods(plugin,"    private static RddCallbackGuard.Ticket replacePlanningTicket("))
                .replace("// @ON_SERVER@", methods(plugin,"    static void onServer(MinecraftServer server,"))
                .replace("// @ACCEPT_PLAN@", method(source(src,"RddGoalDriver"),"    static void acceptPlan(PrimaryGoal owner,"));
        List<String> javac = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-classpath",
                location(TaskChain.class) + File.pathSeparator + location(Gson.class) + File.pathSeparator
                        + location(org.slf4j.LoggerFactory.class) + File.pathSeparator + location(Driver.class), "-d", out.toString()));
        javaSource(javac, HOST, host);
        javaSource(javac, "com.dwinovo.numen.agent.tool.NumenTool", """
                package com.dwinovo.numen.agent.tool;
                import java.util.*; import java.util.function.Consumer; import com.google.gson.JsonObject;
                import com.dwinovo.numen.entity.NumenPlayer;
                public interface NumenTool { String name(); String description(); Map<String,Object> parameterSchema();
                    void onServerCall(String id, JsonObject args, NumenPlayer p, Consumer<String> reply); }
                """);
        javaSource(javac, "com.dwinovo.numen.agent.tool.Schema", """
                package com.dwinovo.numen.agent.tool; import java.util.Map;
                public class Schema { public static Schema object(){return new Schema();}
                    public Schema string(String k,String d){return this;} public Schema optionalString(String k,String d){return this;} public Map<String,Object> build(){return Map.of();} }
                """);
        javaSource(javac, "com.dwinovo.numen.entity.NumenPlayer", """
                package com.dwinovo.numen.entity; import java.util.UUID;
                public record NumenPlayer(UUID id) { public UUID getUUID(){return id;}
                    public Level level(){return new Level();} public static class Level { public long getGameTime(){return 100;} } }
                """);
        for (String type : List.of("RddConcernTool", "RddNegotiationInbox", "RddReplanTransition", "RddSurvivalGate", "RddCallbackGuard", "RddStageBSpecParser", "RddKeys", "RddMainlineAdmission"))
            javac.add(src.resolve(type + ".java").toString());
        javac.add(root.resolve("api/common/src/main/java/com/dwinovo/numen/task/TaskResult.java").toString());
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "SETUP: JDK required");
        assertEquals(0, compiler.run(null, null, null, javac.toArray(String[]::new)), "SETUP: production consumer compilation");
    }

    private static String source(Path src, String type) throws IOException {
        return Files.readString(src.resolve(type + ".java")).replace("\r\n", "\n");
    }
    private static int start(String s, String marker) {
        int at = s.indexOf(marker);
        assertTrue(at >= 0 && at == s.lastIndexOf(marker), "SETUP: missing or ambiguous source marker: " + marker);
        return at;
    }
    private static String declaration(String s, String prefix) {
        int a = start(s, prefix);
        return s.substring(a, s.indexOf(';', a) + 1);
    }
    private static String methods(String s, String signature) {
        StringBuilder result=new StringBuilder();int from=0;
        while(true){int at=s.indexOf(signature,from);if(at<0)return result.toString();
            int end=s.indexOf("\n    }",s.indexOf('{',at));if(end<at)throw new AssertionError("SETUP: method end");
            result.append(s,at,end+6).append('\n');from=end+6;}
    }
    private static String method(String s, String signature) {
        int a = start(s, signature), b = s.indexOf("\n    }", s.indexOf('{', a));
        assertTrue(b > a, "SETUP: missing method end: " + signature);
        return s.substring(a, b + 6);
    }
    private static void javaSource(List<String> args, String type, String body) throws IOException {
        Path f = out.resolve(type.replace('.', '/') + ".java");
        Files.createDirectories(f.getParent());
        Files.writeString(f, body);
        args.add(f.toString());
    }
    private static String location(Class<?> c) throws Exception {
        return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    @BeforeEach void startAttemptAndRequest() throws Exception {
        // A new namespace per case keeps real static inbox/guard/host state isolated, without test-side clear().
        loader = new URLClassLoader(new URL[]{out.toUri().toURL()}, getClass().getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals(Driver.class.getName()) && (name.startsWith(PKG)
                        || name.startsWith("com.dwinovo.numen.entity.NumenPlayer")
                        || name.startsWith("com.dwinovo.numen.agent.tool.") || name.equals("com.dwinovo.numen.task.TaskResult"))) {
                    Class<?> c = findLoadedClass(name);
                    if (c == null) c = findClass(name);
                    if (resolve) resolveClass(c);
                    return c;
                }
                return super.loadClass(name, resolve);
            }
        };
        Subtask sub = Subtask.hardCoded("s", "first step", Map.of("asset_key", "minecraft:iron_ingot", "minimum", 1));
        chain = new TaskChain(new Goal("g", "objective", List.of(new PrimaryGoal("p", "primary", List.of(sub)))));
        rt = new RddRuntime(chain, new AssetRegistry());
        host = (Driver)loader.loadClass(HOST).getConstructor(UUID.class, RddRuntime.class).newInstance(UUID.randomUUID(), rt);
        rt.startCurrent();
        assertEquals(PrimaryGoalStatus.ACTIVE, chain.primaryStatus());
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
        revision = chain.planRevision(); attempt = chain.attempts("s");
        JsonObject reply = report("COUNTER", "first problem", "use another route");
        assertTrue(reply.getAsJsonObject("data").get("replanRequested").getAsBoolean(), reply::toString);
        assertEquals(PrimaryGoalStatus.REPLANNING, chain.primaryStatus());
        assertEquals(1, host.plannerCalls());
        assertEquals(1, host.negotiationBudgetUsed());
        assertEquals(0, host.queued());
        report("PAUSE", OLD, ""); // arrived via real tool while the first planner request is in flight
        assertEquals(OLD, host.pendingReason(), "precondition: this version accepts the in-flight objection");
        assertEquals(1, host.plannerCalls());
        assertEquals(1, host.negotiationBudgetUsed());
    }
    @AfterEach void releaseLoader() throws IOException { if (loader != null) loader.close(); }

    private JsonObject report(String kind, String reason, String suggestion) {
        JsonObject reply = JsonParser.parseString(host.report(kind, chain.currentSubtask().id(), reason, suggestion)).getAsJsonObject();
        assertTrue(reply.get("success").getAsBoolean(), reply::toString);
        return reply;
    }
    private void applyQueued(List<SubtaskSpec> specs, String snapshot) {
        host.complete(specs);
        assertEquals(1, host.queued(), "SETUP: completion must wait on the server executor");
        assertEquals(PrimaryGoalStatus.REPLANNING, chain.primaryStatus());
        assertTrue(host.snapshots().isEmpty(), "no inline application allowed");
        host.runNext();
        assertEquals(0, host.queued());
        assertEquals(List.of(snapshot), host.snapshots(), "original callback must apply once, on the same captured server");
        assertEquals(PrimaryGoalStatus.PENDING, chain.primaryStatus());
        assertEquals(SubtaskStatus.PENDING, chain.currentSubtaskStatus());
    }
    private void activate() {
        assertTrue(rt.activateCurrentFromSnapshot(PlanningAssetSnapshot.from(Map.of(), rt.assets())));
        assertEquals(PrimaryGoalStatus.ACTIVE, chain.primaryStatus());
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
    }
    private void retrySameStep() {
        applyQueued(List.of(), "replan_fallback_resume");
        assertEquals(revision, chain.planRevision());
        assertEquals("s", chain.currentSubtask().id());
        activate(); // activateCurrentFromSnapshot already starts exactly one new attempt
        assertEquals(attempt + 1, chain.attempts("s"));
    }
    private void assertNoSecondPlan() {
        assertEquals(1, host.plannerCalls());
        assertEquals(1, host.negotiationBudgetUsed());
    }

    @Test void inFlightPauseMustNotDirectlyParkRetriedAttempt() {
        retrySameStep();
        int clearedBefore=host.bodyClears();
        host.negotiate();
        assertEquals(clearedBefore,host.bodyClears(),"stale concern cannot clear the new body owner");
        assertNoSecondPlan();
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus(),
                () -> "old PAUSE directly parked the new attempt: " + chain.pauseReason("s") + "; pause events=" + host.reasons("subtask_paused"));
        assertFalse(host.reasons("subtask_paused").contains(OLD));
        assertEquals(OLD, host.review().get("reason_reported"));
        assertEquals("DEFERRED", host.review().get("premise_disposition"));
    }
    @Test void explicitReviewCarriesOriginalEntryAndUnverifiedFixedStaysDeferred() {
        retrySameStep(); host.negotiate();
        String ref = (String) host.review().get("concern_ref");
        assertFalse(JsonParser.parseString(host.requestReview("", "new evidence without a ref")).getAsJsonObject().get("success").getAsBoolean());
        assertFalse(JsonParser.parseString(host.requestReview(ref, "")).getAsJsonObject().get("success").getAsBoolean());
        assertEquals(1, host.plannerCalls());
        assertTrue(JsonParser.parseString(host.requestReview(ref, "new owner checked route; needs planner judgment")).getAsJsonObject().get("success").getAsBoolean());
        assertEquals(2, host.plannerCalls());
        assertTrue(host.reviewPrompt().contains(ref)); assertTrue(host.reviewPrompt().contains(OLD));
        assertEquals("REVIEW_REQUESTED", host.review().get("premise_disposition"));
        assertFalse(JsonParser.parseString(host.requestReview(ref, "new owner checked route; needs planner judgment")).getAsJsonObject().get("success").getAsBoolean());
        assertEquals(2, host.negotiationBudgetUsed());
        host.completeReview("{\"subtasks\":[],\"concern_review\":{\"concern_ref\":\"" + ref
                + "\",\"proposal\":\"FIXED\",\"reason\":\"model says route is clear\",\"basis\":\"unverified text\"}}");
        assertEquals(1, host.queued()); host.runNext();
        assertEquals("DEFERRED", host.review().get("premise_disposition"));
        assertEquals(OLD, host.review().get("reason_reported"));
        assertTrue(host.review().containsKey("untrusted_planner_proposal"));
        activate(); host.negotiate();
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
        assertFalse(JsonParser.parseString(host.requestReview(ref, "new owner checked route; needs planner judgment")).getAsJsonObject().get("success").getAsBoolean());
        assertEquals(2, host.plannerCalls()); assertEquals(2, host.negotiationBudgetUsed());
    }
    @Test void occupiedReviewRejectsOverwriteAndFreshControlStillPauses() {
        retrySameStep(); host.negotiate();
        String ref = (String)host.review().get("concern_ref");
        report("PAUSE", FRESH, ""); host.negotiate();
        assertEquals(SubtaskStatus.PAUSED, chain.currentSubtaskStatus());
        assertEquals(ref, host.review().get("concern_ref"));
        assertEquals(OLD, host.review().get("reason_reported"));
    }
    @Test void lateReviewCannotEraseFreshControlAndOccupiedReviewReportsRetry() {
        retrySameStep(); host.negotiate(); String ref = (String)host.review().get("concern_ref");
        assertTrue(JsonParser.parseString(host.requestReview(ref,"new evidence for current owner")).getAsJsonObject().get("success").getAsBoolean());
        JsonObject refused = JsonParser.parseString(host.report("PAUSE",chain.currentSubtask().id(),"another in-flight concern","")).getAsJsonObject();
        assertFalse(refused.get("success").getAsBoolean()); assertTrue(refused.toString().contains("review_pending"));
        assertEquals(OLD,host.review().get("reason_reported"));
        host.completeReview("{\"subtasks\":[]}");
        chain.resumeFromReplanning(); activate();
        report("PAUSE",FRESH,""); host.runNext();
        assertEquals(FRESH,host.pendingReason());
        host.negotiate(); assertEquals(SubtaskStatus.PAUSED,chain.currentSubtaskStatus());
        assertEquals(List.of(FRESH),host.reasons("subtask_paused"));
        assertEquals(OLD,host.review().get("reason_reported"));
    }
    @Test void deathInvalidatesPendingReviewWithoutPermanentlyOccupyingItsSlot() {
        retrySameStep();host.negotiate();String ref=(String)host.review().get("concern_ref");
        assertTrue(JsonParser.parseString(host.requestReview(ref,"before-death evidence")).getAsJsonObject().get("success").getAsBoolean());
        host.completeReview("{\"subtasks\":[]}");assertEquals(1,host.queued());
        host.invalidateForDeath();
        assertEquals("DEFERRED",host.review().get("premise_disposition"));
        assertEquals(OLD,host.review().get("reason_reported"));
        assertTrue(host.review().get("host_reason").toString().contains("invalidated"));
        activate();report("PAUSE",FRESH,"");
        host.runNext();assertEquals(FRESH,host.pendingReason());
        assertTrue(JsonParser.parseString(host.requestReview(ref,"after-death explicit current observation")).getAsJsonObject().get("success").getAsBoolean());
        assertEquals(3,host.plannerCalls());
    }
    @Test void originalPlanningTimeoutDefersLostReviewAndAllowsExplicitNewEvidence() {
        retrySameStep();host.negotiate();String ref=(String)host.review().get("concern_ref");
        assertTrue(JsonParser.parseString(host.requestReview(ref,"before-timeout evidence")).getAsJsonObject().get("success").getAsBoolean());
        host.planningTimeout();
        assertEquals(PrimaryGoalStatus.PENDING,chain.primaryStatus());
        assertEquals("DEFERRED",host.review().get("premise_disposition"));
        assertEquals(OLD,host.review().get("reason_reported"));
        activate();
        assertTrue(JsonParser.parseString(host.requestReview(ref,"after-timeout new owner observation")).getAsJsonObject().get("success").getAsBoolean());
        assertEquals(3,host.plannerCalls());
    }
    @Test void emptyReviewAndBudgetRefusalKeepActionableDeferredEntry() {
        retrySameStep(); host.negotiate(); String ref = (String)host.review().get("concern_ref");
        assertTrue(JsonParser.parseString(host.requestReview(ref,"owner explicitly requests new observation A")).getAsJsonObject().get("success").getAsBoolean());
        host.completeReview("{\"subtasks\":[]}"); host.runNext(); activate();
        assertEquals("DEFERRED",host.review().get("premise_disposition"));
        assertFalse(((String)host.review().get("resume_condition")).isBlank());
        host.exhaustBudget();
        assertFalse(JsonParser.parseString(host.requestReview(ref,"owner explicitly requests new observation B")).getAsJsonObject().get("success").getAsBoolean());
        assertEquals("DEFERRED",host.review().get("premise_disposition"));
        assertEquals(OLD,host.review().get("reason_reported")); assertEquals(2,host.plannerCalls());
    }

    @Test void freshPauseAfterFallbackAppliesToTheRetriedAttempt() {
        retrySameStep();
        report("PAUSE", FRESH, "");
        host.negotiate();
        assertEquals(SubtaskStatus.PAUSED, chain.currentSubtaskStatus());
        assertEquals(List.of(FRESH), host.reasons("subtask_paused"));
        assertTrue(chain.pauseReason("s").contains(FRESH));
        assertNoSecondPlan();
    }
    @Test void installedReplacementDropsTheOldPauseAndChangesIdentity() {
        applyQueued(List.of(new SubtaskSpec("replacement", Map.of("asset_key", "minecraft:bread", "minimum", 1), null)), "replanned");
        assertEquals(revision + 1, chain.planRevision());
        assertEquals("p-r" + (revision + 1) + "-0", chain.currentSubtask().id());
        assertNull(host.pendingReason(), "afterApplied must remove the old execution entry");
        activate();
        host.negotiate();
        assertEquals(SubtaskStatus.RUNNING, chain.currentSubtaskStatus());
        assertTrue(host.reasons("subtask_paused").isEmpty());
        assertNoSecondPlan();
    }
}
