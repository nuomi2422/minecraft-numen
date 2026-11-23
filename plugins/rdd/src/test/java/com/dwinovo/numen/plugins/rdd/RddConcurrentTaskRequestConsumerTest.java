package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.TaskChain;
import com.google.gson.Gson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test-only request-consumer probe. No production/other-test/shared-fixture changes.
 * STATUS: NOT EXECUTED. Run only in the integrator's serialized test slot.
 * Compiler/extraction/fixture errors are SETUP failures, never assumptions or passing assertions.
 * The concurrent visibility assertion is intentionally NOT inverted to accept today's projection.
 *
 * Real: DAILY registration, core engine/types/chain, host refreshContext, cache/decorator,
 * renderStateContext, all three state registration statements, state registry/aggregation,
 * loop sections/allocation/XML/snapshot and AgentRequestContext.attach.
 * Boundaries: server identity + world observations; empty asset augmentation, planner notice,
 * negotiation/retrieval services, and empty body/inventory/effects/riding/history.
 * Accepted-owner rationale binding and its request section use unchanged production sources.
 * NOT COVERED: actual MC tick scheduling, why/resources, network/provider serialization,
 * delivery journal, budget pressure, model behavior. Empty replay is a legal attach positive control.
 */
class RddConcurrentTaskRequestConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;
    static Class<?> scenario;
    static Path root;
    static final String P = "com/dwinovo/numen/plugins/rdd/";
    static final String A = "com/dwinovo/numen/api/";
    static final String C = "com/dwinovo/numen/client/agent/";

    @BeforeAll static void setup() throws Exception {
        root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("plugins/rdd/src/main/java"))) root = root.getParent();
        assertNotNull(root, "SETUP: repository root not found");
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "SETUP: full JDK required");
        Map<String,String> units = new LinkedHashMap<>();
        for (String name : List.of("SideContextStore", "SideContextDecorator", "RddBasePlaybook", "RddSurvivalDoctrine", "RddRationale"))
            copy(units, P + name + ".java", "plugins/rdd/src/main/java/");
        for (String name : List.of("StateFragment", "StateFragmentRegistry", "ExecutorRetrievalInput", "RationaleContext"))
            copy(units, A + name + ".java", "api/common/src/main/java/");
        copy(units, C + "AgentRequestContext.java", "api/common/src/client/java/");
        copy(units, "com/dwinovo/numen/agent/llm/ConvoState.java", "ai/src/main/java/");
        copy(units, "com/dwinovo/numen/agent/llm/CompactSplit.java", "ai/src/main/java/");
        copy(units, "com/dwinovo/numen/agent/context/ContextBudget.java", "ai/src/main/java/");
        String host = source("plugins/rdd/src/main/java/" + P + "SideTaskHost.java");
        String plugin = source("plugins/rdd/src/main/java/" + P + "RddPlugin.java");
        String np = source("api/common/src/main/java/" + A + "NumenPlugins.java");
        String loop = source("api/common/src/client/java/" + C + "EntityAgentLoop.java");
        units.put(P + "SideTaskHost.java", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*; import com.dwinovo.numen.rdd.api.*; import com.dwinovo.numen.rdd.core.*;
            import com.dwinovo.numen.rdd.side.*; import org.slf4j.*;
            class SideTaskHost {
              static SideTaskEngine ENGINE;
              static final SideContextStore SIDE_CONTEXT = new SideContextStore();
            """ + between(host, "    static final class DailyRegistration {", "\n    static boolean isActive(")
                + method(host, "    private static void refreshContext(UUID companionId, SideTaskWorldPort port) {")
                + method(host, "    private static void refreshContext(UUID companionId, SideTaskWorldPort port, Map<String, String> stamp)")
                + method(host, "    static String contextFor(UUID companionId, String")
                + method(host, "    static String registrationContextFor(")
                + method(host, "    private static String contextXml(") + "}");
        String driver = source("plugins/rdd/src/main/java/" + P + "RddGoalDriver.java");
        units.put(P + "RddGoalDriver.java", "package com.dwinovo.numen.plugins.rdd; import java.util.*; import com.dwinovo.numen.rdd.api.*; class RddGoalDriver {"
            + method(driver, "    static List<Subtask> buildInjection(")
            + method(driver, "    static void acceptPlan(List<Subtask>")
            + method(driver, "    static void acceptPlan(PrimaryGoal") + "}");
        units.put(P + "RddPlugin.java", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*; import com.dwinovo.numen.rdd.api.*; import com.dwinovo.numen.rdd.core.*;
            import net.minecraft.server.level.ServerPlayer;
            public class RddPlugin {
              static final Map<UUID,RddRuntime> RUNTIMES = new HashMap<>();
              static final Set<UUID> DECOMPOSING = new HashSet<>();
              static final Map<UUID,String> LAST_CONTEXT = new HashMap<>();
              static RddRuntime runtime(UUID id){ return RUNTIMES.get(id); }
              static String withAssets(UUID id,String text){ return text; } // asset boundary, NOT task rendering
              static String earlyProposalNoticeFor(UUID id){ return ""; } // no outstanding planner proposal
              static Map<String,Object> observationData(UUID id){ return new HashMap<>(); }
              public static void register(){ var numen = new com.dwinovo.numen.api.NumenPlugins.Registration();
            """ + between(plugin, "        numen.contributeState(\"rdd\", \"runtime\"", "\n    }\n\n    /** Model-requested")
            + "}\n" + method(plugin, "    private static String renderStateContext(")
            + method(plugin, "    private static String resourceUseContext(")
            + method(plugin, "    private static String registeredDailyContext(")
            + method(plugin, "    private static boolean registrationSampleCurrent(")
            + method(plugin, "    private static boolean registrationFieldUnknown(")
            + method(plugin, "    private static String sideContextFor(")
            + method(plugin, "    static String retrievalTaskFields(")
            + method(plugin, "    private static String renderExecutionReasons(")
            + method(plugin, "    private static String renderBasePlaybook(")
            + method(plugin, "    private static String escape(") + "}");
        units.put(A + "NumenPlugins.java", """
            package com.dwinovo.numen.api; import java.util.*; import java.util.function.*;
            import com.dwinovo.numen.Constants;
            public class NumenPlugins {
              private static final StateFragmentRegistry STATE = new StateFragmentRegistry();
              public static class Registration {
            """ + method(np, "        public void contributeState(String owner") + "}\n"
            + between(np, "    public static List<StateFragment> stateFragmentEntries(", "\n    private static final List<Runnable> PENDING")
            + """
              public static <T> T capability(Class<T> c){ return c == ServerIdentityProvider.class ? c.cast((ServerIdentityProvider)(s -> new ServerIdentityProvider.Snapshot("world", Identity.session))) : null; }
              public static List<Object> executorContributors(){ return List.of(); }
              public record KnowledgeFragment(String text,List<Object> refs){}
              public static KnowledgeFragment aggregateExecutorKnowledgeFragment(List<Object> c,UUID u,String q){ return new KnowledgeFragment("",List.of()); }
            }
            """);
        units.put(C + "EntityAgentLoop.java", """
            package com.dwinovo.numen.client.agent;
            import java.util.*; import com.dwinovo.numen.agent.context.ContextBudget;
            import com.dwinovo.numen.agent.llm.*;
            public class EntityAgentLoop {
              UUID entityUuid = new UUID(0,1);
              String pendingRuntimeStateXml,pendingDeliveryTaskId,renderedDeliveryTaskId,pendingRuntimeOrigin;
              int pendingRuntimeAttempt; ContextBudget.Allocation pendingRuntimeStatePlan; List<Object> pendingDeliveredRefs;
              record Goal(String objective){} Goal goal; interface Body {String describe();} Body currentTask;
              void maybeSegmentSession(){} List<ConvoState.Msg> replayWindow(boolean p){ return List.of(); }
              String currentTaskXml(){return "";} String inventoryXml(){return "";} String effectsXml(){return "";} String ridingXml(){return "";}
              public String finalUser(){ var out = modelContextSnapshot(false,"execution",1); if(out.size()!=1 || !(out.get(0) instanceof ConvoState.Msg.User)) throw new AssertionError("SETUP: attach did not produce user"); return ((ConvoState.Msg.User)out.get(0)).content(); }
            """ + method(loop, "    private List<ConvoState.Msg> modelContextSnapshot(")
            + method(loop, "    private String runtimeStateXml(")
            + between(loop, "    private static final String SECTION_CURRENT_TASK", "\n    /**\n     * 上下文预算闸")
            + method(loop, "    private static int estimateTokensOfText(") + "}");
        boundaryUnits(units);
        var args = new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-sourcepath",temp.toString(),"-classpath", classpath(), "-d",temp.toString()));
        for (var entry : units.entrySet()) {
            Path file = temp.resolve(entry.getKey()); Files.createDirectories(file.getParent()); Files.writeString(file, entry.getValue()); args.add(file.toString());
        }
        var diagnostics = new ByteArrayOutputStream();
        int status = ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, args.toArray(String[]::new));
        assertEquals(0, status, "SETUP: isolated consumer compilation failed (not a business result):\n" + diagnostics);
        loader = new URLClassLoader(new URL[]{temp.toUri().toURL()}, RddConcurrentTaskRequestConsumerTest.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
                if (Files.exists(temp.resolve(n.replace('.', '/') + ".class"))) {
                    Class<?> type = findLoadedClass(n); if(type == null) type = findClass(n); if(resolve) resolveClass(type); return type;
                }
                return super.loadClass(n, resolve);
            }
        };
        scenario = loader.loadClass("com.dwinovo.numen.plugins.rdd.ConcurrentScenario");
    }

    @AfterAll static void close() throws Exception { if(loader != null) loader.close(); }

    @Test void declaredResourceUsesReachCurrentAndRelatedCandidateFinalUserWithoutBecomingEvidence() throws Exception {
        for(String kind:List.of("resource","resource-side")) {
            String user=request(kind,false);
            assertTrue(user.contains("MAIN_IRON_73"));
            assertTrue(user.contains("DECLARED_INTENT_NOT_EVIDENCE"),user);
            assertTrue(user.contains("CONSUME"),user);
            assertTrue(user.contains("RETAIN"),user);
            assertTrue(user.contains("minecraft:cobblestone"),user);
            assertFalse(user.contains("consumptionConfirmed"));
        }
    }

    @Test void mainOnlyPositiveControlReachesAttachedUser() throws Exception {
        String user = request("main", false);
        assertTrue(user.contains("MAIN_IRON_73"), "real main renderer must survive attach");
        assertTrue(user.contains("minecraft:iron_ingot"));
    }
    @Test void activeDailyPositiveControlReachesAttachedUser() throws Exception {
        String user = request("daily", false);
        assertTrue(user.contains("DAILY_WHEAT_91"));
        assertTrue(user.contains("minecraft:wheat"));
        assertTrue(user.contains("CURRENT"));
    }
    @Test void mainSideAndRegisteredDailyMustBeVisibleTogetherInOneFinalUser() throws Exception {
        String user = request("side", false);
        assertAll("concurrent visibility contract, not an expected-failure inversion",
            () -> assertTrue(user.contains("MAIN_IRON_73"), "main task disappeared from final request: " + user),
            () -> assertTrue(user.contains("[survival]"), "real food side missing"),
            () -> assertTrue(user.contains("DAILY_WHEAT_91"), "registered DAILY disappeared from final request: " + user),
            () -> assertTrue(user.contains("minecraft:wheat"), "DAILY completion condition missing"));
    }
    @Test void staleSessionMustNotDeliverOldSideFacts() throws Exception {
        String current = request("side", false), stale = request("side", true);
        assertTrue(current.contains("[survival]"), "positive control required before stale rejection");
        assertTrue(stale.contains("UNKNOWN"));
        assertFalse(stale.contains("[survival]"));
        assertFalse(stale.contains("hunger=3"));
        assertFalse(stale.contains("MAIN_IRON_73"), "unknown side identity must not authorise related candidates");
        assertFalse(stale.contains("DAILY_WHEAT_91"));
    }
    @Test void staleDailyMustNotDeliverOldDescriptionOrCondition() throws Exception {
        String current = request("daily", false), stale = request("daily", true);
        assertTrue(current.contains("DAILY_WHEAT_91"));
        assertTrue(stale.contains("UNKNOWN"));
        assertFalse(stale.contains("DAILY_WHEAT_91"));
        assertFalse(stale.contains("minecraft:wheat"));
    }
    @Test void acceptedOwnerReasonAndWaitingConditionReachSameRequest() throws Exception {
        String user=request("why",false);
        assertTrue(user.contains("MAIN_IRON_73"));
        assertTrue(user.contains("WHY_IRON_FOR_SAFE_EXPEDITION_47"));
        assertTrue(user.contains("WAIT_UNTIL_DAYLIGHT_63"));
        assertTrue(user.contains("RISK_NIGHT_51"));
    }
    @Test void oldRevisionReasonCannotReachNewRequest() throws Exception {
        String user=request("why-revision",false);
        assertTrue(user.contains("MAIN_IRON_73"));
        assertFalse(user.contains("WHY_IRON_FOR_SAFE_EXPEDITION_47"));
        assertFalse(user.contains("WAIT_UNTIL_DAYLIGHT_63"));
    }
    @Test void sameTaskIdInReplacementChainCannotInheritOldReason() throws Exception {
        String user=request("why-chain",false);
        assertTrue(user.contains("MAIN_IRON_73"));
        assertFalse(user.contains("WHY_IRON_FOR_SAFE_EXPEDITION_47"));
        assertFalse(user.contains("WAIT_UNTIL_DAYLIGHT_63"));
    }
    @Test void relatedCandidatesDoNotReplaceCurrentSideOrAuthoriseBody() throws Exception {
        String user=request("side",false);
        assertTrue(user.contains("<primary_status>SIDE_TASK</primary_status>"));
        assertTrue(user.contains("[survival]"));
        assertTrue(user.contains("WAIT_FOR_SIDE_RELEASE"));
        assertTrue(user.contains("PLANNED_TEMPLATE_REGISTRATION_AND_DUE_UNKNOWN"));
        assertTrue(user.contains("不可据此抢占身体"));
        assertEquals(1,user.split("<rdd>",-1).length-1);
    }
    @Test void finishedMainIsNotResurrectedAsSideCandidate() throws Exception {
        String user=request("finished-side",false);
        assertTrue(user.contains("[survival]"));
        assertFalse(user.contains("MAIN_IRON_73"));
        assertFalse(user.contains("<related_tasks>"));
    }
    @Test void planningDoesNotExposeOldRelatedCandidatesWhileSideRemainsCurrent() throws Exception {
        String user=request("decomposing-side",false);
        assertTrue(user.contains("[survival]"));
        assertFalse(user.contains("<related_tasks>"));
        assertFalse(user.contains("MAIN_IRON_73"));
    }
    @Test void currentOwnerReasonRemainsVisibleWhileSideOwnsExecution() throws Exception {
        String user=request("why-side",false);
        assertTrue(user.contains("[survival]"));
        assertTrue(user.contains("WHY_IRON_FOR_SAFE_EXPEDITION_47"));
        assertTrue(user.contains("WAIT_FOR_SIDE_RELEASE"));
    }
    @Test void sideEarlyReturnCannotDeliverPreviousRevisionReason() throws Exception {
        String user=request("why-side-revision",false);
        assertTrue(user.contains("[survival]"));
        assertFalse(user.contains("WHY_IRON_FOR_SAFE_EXPEDITION_47"));
        assertFalse(user.contains("WAIT_UNTIL_DAYLIGHT_63"));
    }
    @Test void sideEarlyReturnCannotDeliverPreviousChainReason() throws Exception {
        String user=request("why-side-chain",false);
        assertTrue(user.contains("[survival]"));
        assertFalse(user.contains("WHY_IRON_FOR_SAFE_EXPEDITION_47"));
        assertFalse(user.contains("WAIT_UNTIL_DAYLIGHT_63"));
    }
    @Test void delegatedDailyRemainsOnlyAPlanTemplateNotRegisteredOrDueFact() throws Exception {
        String user=request("side",false);
        assertTrue(user.contains("DAILY_WHEAT_91"));
        assertTrue(user.contains("<source>plan</source>"));
        assertTrue(user.contains("PLANNED_TEMPLATE_REGISTRATION_AND_DUE_UNKNOWN"));
    }
    static String request(String kind, boolean stale) throws Exception {
        try { return (String) scenario.getMethod("request",String.class,boolean.class).invoke(null,kind,stale); }
        catch (java.lang.reflect.InvocationTargetException e) { throw new AssertionError("SETUP/request failed before business assertions", e.getCause()); }
    }
    static String source(String path) throws IOException { return Files.readString(root.resolve(path)); }
    static void copy(Map<String,String> units,String path,String prefix) throws IOException { units.put(path,source(prefix+path)); }
    static String between(String text,String begin,String end) {
        int a=text.indexOf(begin), b=a<0?-1:text.indexOf(end,a+begin.length());
        assertTrue(a>=0 && b>a,"SETUP: source region missing: " + begin + " -> " + end);
        return text.substring(a,b);
    }
    // Methods close at their declaration indentation. Does not rewrite their bodies or copy task logic.
    static String method(String text,String signature) {
        int a=text.indexOf(signature); assertTrue(a>=0,"SETUP: method missing: "+signature);
        int indent=signature.indexOf(signature.stripLeading());
        String close="\n"+" ".repeat(indent)+"}";
        int b=text.indexOf(close,a); assertTrue(b>a,"SETUP: method end missing: "+signature);
        return text.substring(a,b+close.length())+"\n";
    }
    static String classpath() throws Exception {
        var entries = new LinkedHashSet<String>(List.of(System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))));
        for (Class<?> c : List.of(TaskChain.class,Gson.class,com.dwinovo.numen.agent.llm.ConvoState.class))
            entries.add(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        return String.join(File.pathSeparator,entries);
    }
    static void boundaryUnits(Map<String,String> u) throws Exception {
        u.put("net/neoforged/fml/loading/FMLPaths.java", "package net.neoforged.fml.loading; public class FMLPaths {public static final java.util.function.Supplier<java.nio.file.Path> GAMEDIR=() -> java.nio.file.Path.of(\".\");}");
        u.put(A+"Identity.java", "package com.dwinovo.numen.api; public class Identity {public static String session=\"current\";}");
        u.put(A+"WorldContextStamp.java", "package com.dwinovo.numen.api; import java.util.*; public class WorldContextStamp {public static Map<String,String> snapshot(){return Map.of(\"world_id\",\"world\",\"server_session\",\"current\");}}");
        u.put(A+"ServerIdentityProvider.java", "package com.dwinovo.numen.api; public interface ServerIdentityProvider {record Snapshot(String worldId,String serverSession){} Snapshot snapshot(Object server);}");

        u.put("com/dwinovo/numen/Constants.java", "package com.dwinovo.numen; public class Constants {public static final org.slf4j.Logger LOG=org.slf4j.LoggerFactory.getLogger(Constants.class);}");
        u.put("org/slf4j/Logger.java", "package org.slf4j; public interface Logger {default void info(String s,Object...a){} default void error(String s,Object...a){throw new AssertionError(s, a.length>0 && a[0] instanceof Throwable ? (Throwable)a[0] : null);}}");
        u.put("org/slf4j/LoggerFactory.java", "package org.slf4j; public class LoggerFactory {public static Logger getLogger(Class<?> c){return new Logger(){};}}");
        u.put("net/minecraft/server/level/ServerPlayer.java", "package net.minecraft.server.level; public class ServerPlayer {public ServerPlayer level(){return this;} public Object getServer(){return this;}}");
        u.put(P+"RddCompanionBody.java", "package com.dwinovo.numen.plugins.rdd; class RddCompanionBody {static net.minecraft.server.level.ServerPlayer get(java.util.UUID id){return new net.minecraft.server.level.ServerPlayer();}}");

        u.put(P+"RddNegotiationInbox.java", "package com.dwinovo.numen.plugins.rdd; class RddNegotiationInbox {static Object reviewSnapshot(java.util.UUID id){return java.util.List.of();}}");
        u.put(P+"RddMonitor.java", "package com.dwinovo.numen.plugins.rdd; class RddMonitor {static void publish(String event,java.util.Map<String,Object> data){}}");
        u.put("com/dwinovo/numen/agent/llm/BudgetPolicy.java", "package com.dwinovo.numen.agent.llm; public class BudgetPolicy {public static com.dwinovo.numen.agent.context.ContextBudget contextBudget(){return com.dwinovo.numen.agent.context.ContextBudget.unlimited();}}");
        u.put(P+"ConcurrentScenario.java", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*; import com.dwinovo.numen.rdd.api.*; import com.dwinovo.numen.rdd.core.*;
            import com.dwinovo.numen.rdd.side.*;
            public class ConcurrentScenario {
              static boolean registered;
              public static String request(String kind,boolean stale){
                UUID id=new UUID(0,1);
                com.dwinovo.numen.api.Identity.session="current";
                com.dwinovo.numen.api.RationaleContext.clear(id);
                SideTaskHost.SIDE_CONTEXT.clear();
                RddPlugin.DECOMPOSING.clear();
                TaskChain chain=new TaskChain(new Goal("g","iron expedition",List.of(PrimaryGoal.unexpanded("p","prepare iron",List.of()))));
                var subs=RddGoalDriver.buildInjection("p",1,List.of(
                  new SubtaskSpec("DAILY_WHEAT_91",Map.of("asset_key","minecraft:wheat","minimum",4),null,NodeType.DAILY,RecurrenceSpec.daily(false)),
                  new SubtaskSpec("MAIN_IRON_73",Map.of("asset_key","minecraft:iron_ingot","minimum",7),null,NodeType.MAIN,null,
                    kind.startsWith("resource") ? List.of(new ResourceUse("minecraft:cobblestone",8,ResourceUse.Kind.CONSUME),
                      new ResourceUse("minecraft:cobblestone",7,ResourceUse.Kind.RETAIN)) : List.of())));
                SideTaskEngine engine=new SideTaskEngine(null,null);
                SideTaskHost.ENGINE=engine;
                engine.registerType(new DailyMaintenanceSideTaskType()); engine.registerType(new FoodSupplySideTaskType());
                RddGoalDriver.acceptPlan(subs,c -> SideTaskHost.DailyRegistration.register(chain,c,engine),() -> chain.expandCurrentPrimary(subs));
                if(!chain.activateCurrent(Map.of())) throw new AssertionError("SETUP: main activation");
                String specId="daily:g:"+Integer.toHexString("DAILY_WHEAT_91".hashCode());
                var daily=engine.recurringSpec(specId);
                if(daily==null || !"DAILY_WHEAT_91".equals(daily.data().get("description"))) throw new AssertionError("SETUP: DAILY registration");
                RddPlugin.RUNTIMES.put(id,new RddRuntime(chain,new AssetRegistry()));
                if(kind.startsWith("why")) {
                  var why=new com.dwinovo.numen.api.RationaleContext.Entry("WHY_IRON_FOR_SAFE_EXPEDITION_47","RISK_NIGHT_51","accepted_owner","WAIT_UNTIL_DAYLIGHT_63");
                  RddRationale.bound(id,chain,why,() -> RddPlugin.runtime(id).chain());
                  if(kind.equals("why-revision") || kind.equals("why-side-revision")) chain.nextPlanRevision();
                  if(kind.equals("why-chain") || kind.equals("why-side-chain")) {
                    var replacement=new TaskChain(chain.goal()); replacement.startCurrent();
                    if(!replacement.currentSubtask().id().equals(chain.currentSubtask().id())) throw new AssertionError("SETUP: same-id chain replacement");
                    RddPlugin.RUNTIMES.put(id,new RddRuntime(replacement,new AssetRegistry()));
                  }
                }
                SideTaskWorldPort world=new SideTaskWorldPort(){
                  public long gameTime(){return 1200;} public long dayTime(){return 1200;} public long gameDay(){return 1;}
                  public boolean hasDayNightCycle(){return true;} public String worldId(){return "minecraft:overworld";}
                  public boolean isSleeping(UUID u){return false;} public boolean isDay(){return true;} public Wake pollWake(UUID u){return Wake.NONE;}
                  public int hunger(UUID u){return 3;} public int health(UUID u){return 5;} public int edibleCount(UUID u){return 0;}
                };
                if(kind.equals("finished-side")) chain.applyHardCodedResult(chain.currentSubtask().id(),true);
                if(kind.equals("side") || kind.equals("daily") || kind.equals("finished-side") || kind.equals("decomposing-side") || kind.startsWith("why-side") || kind.equals("resource-side")) {
                  SideTaskType type=kind.equals("daily")?new DailyMaintenanceSideTaskType():new FoodSupplySideTaskType();
                  // Payload comes from the REAL registration, never a rendered prompt or store.put.
                  if(!engine.startNow(type,id,world,null,kind.equals("daily")?daily.data():Map.of())) throw new AssertionError("SETUP: side activation");
                  try {var refresh=SideTaskHost.class.getDeclaredMethod("refreshContext",UUID.class,SideTaskWorldPort.class);refresh.setAccessible(true);refresh.invoke(null,id,world);}
                  catch(ReflectiveOperationException e){throw new AssertionError("SETUP: real host refresh",e);}
                }
                if(kind.equals("decomposing-side")) RddPlugin.DECOMPOSING.add(id);
                if(stale) com.dwinovo.numen.api.Identity.session="replacement-session";
                if(!registered){RddPlugin.register();registered=true;}
                String before=chain.toJson();
                String user=new com.dwinovo.numen.client.agent.EntityAgentLoop().finalUser();
                if(!before.equals(chain.toJson())) throw new AssertionError("read-only request projection changed task state");
                return user;
              }
            }
            """);
    }
}
