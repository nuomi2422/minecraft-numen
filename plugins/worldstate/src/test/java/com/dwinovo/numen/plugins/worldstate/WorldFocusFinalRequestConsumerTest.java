package com.dwinovo.numen.plugins.worldstate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual registration statements, projector, aggregation and request-builder method slices.
 * MC host/observation, inventory/task/voice and network are controlled boundaries, not a live agent.
 * Includes owner release, controlled mandatory-budget refusal, unknown-world and TTL controls.
 * Does NOT claim full plugin setup, managed retirement, natural safety samples or planner requests. */
class WorldFocusFinalRequestConsumerTest {
    @TempDir Path temp;
    @Test void registeredFocusReachesActualExecutionRequest() throws Exception { run(false); }
    @Test void upstreamKnowledgeBudgetNeverDeliversPartialFocus() throws Exception { run(true); }
    private void run(boolean crowdedOnly) throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.exists(root.resolve("plugins/worldstate/src/main/java"))) root=root.getParent();
        assertNotNull(root);
        var sources=new LinkedHashMap<String,String>();
        String plugin=Files.readString(root.resolve("plugins/worldstate/src/main/java/com/dwinovo/numen/plugins/worldstate/WorldStatePlugin.java"));
        int a=plugin.indexOf("        numen.contributePlanningKnowledge(query ->"),b=plugin.indexOf("        numen.registerTool(new WorldProbeTool",a);
        assertTrue(a>=0&&b>a,"exact production contributor registrations");
        sources.put("net.minecraft.server.MinecraftServer","package net.minecraft.server; public class MinecraftServer {}");
        sources.put("net.neoforged.neoforge.server.ServerLifecycleHooks","package net.neoforged.neoforge.server; public class ServerLifecycleHooks { public static net.minecraft.server.MinecraftServer server=new net.minecraft.server.MinecraftServer(); public static net.minecraft.server.MinecraftServer getCurrentServer(){return server;} }");
        sources.put("com.dwinovo.numen.plugins.worldstate.WorldStateService", """
            package com.dwinovo.numen.plugins.worldstate;
            public class WorldStateService {
              private final com.dwinovo.numen.plugins.worldstate.focus.FocusPinStore pins=new com.dwinovo.numen.plugins.worldstate.focus.FocusPinStore();
              public com.dwinovo.numen.plugins.worldstate.focus.FocusPinStore focusPins(){return pins;}
              public static String currentWorld="world";
              public static String worldId(net.minecraft.server.MinecraftServer s){return currentWorld;}
              public String sessionId(net.minecraft.server.MinecraftServer s){return "session";}
            }
            """);
        sources.put("com.dwinovo.numen.plugins.worldstate.RegistrationProbe", """
            package com.dwinovo.numen.plugins.worldstate;
            import com.dwinovo.numen.api.*;
            import com.dwinovo.numen.api.observation.*;
            import java.util.*;
            public class RegistrationProbe {
              static WorldStateService mounted;
              static java.util.List<PlanningKnowledgeContributor> registeredPlanning;
              public static String planning(UUID id){return NumenPlugins.aggregatePlanningKnowledge(registeredPlanning,new PlanningQuery(id,"goal","stage_a",List.of()));}
              public static void pressurePlanning(){registeredPlanning.add(0,q->"P".repeat(NumenPlugins.MAX_PLANNING_KNOWLEDGE_CHARS));}
              static com.dwinovo.numen.plugins.worldstate.focus.FocusPinStore.Lease first,second;
              public static void release(UUID id,boolean last){var lease=last?second:first;mounted.focusPins().release(id,last?"second":"owner",lease.leaseId());}
              public static void switchWorld(boolean known){WorldStateService.currentWorld=known?"world":"UNKNOWN";}
              public static long expiry(UUID id){return mounted.focusPins().active(id,"world","session",System.currentTimeMillis()).getFirst().expiresAtMs();}
              public static String register(UUID id) {
                WorldStateService service=new WorldStateService(); long now=System.currentTimeMillis();
                mounted=service;
                first=service.focusPins().acquire(id,"world","session","minecraft:overworld","environment","v1","owner",now,60000,0,0,0);
                second=service.focusPins().acquire(id,"world","session","minecraft:overworld","environment","v1","second",now,60000,0,0,0);
                WorldObservationService observations=request->{long t=now;
                  var m=new ObservationMetadata("world","session","minecraft:overworld",id.toString(),"controlled-observation",request.topic(),1,t,ObservationMetadata.Freshness.CURRENT,"INTERNAL",t+60000);
                  return new WorldObservationService.Response(WorldObservationService.Status.OK,request.topic(),"snap",1,WorldObservationService.Coverage.FULL,"",List.of(WorldObservationService.Item.of(Map.of("focus_marker","FOCUS_VISIBLE"))),0,m);};
                java.util.List<PlanningKnowledgeContributor> planning=new ArrayList<>(); registeredPlanning=planning;
                class RegistrationHost {
                  void contributePlanningKnowledge(PlanningKnowledgeContributor callback){planning.add(callback);}
                  void contributeExecutorKnowledge(java.util.function.BiFunction<UUID,String,String> callback){NumenPlugins.contributeExecutorKnowledge(callback);}
                }
                RegistrationHost numen=new RegistrationHost();
            """+plugin.substring(a,b)+"""
                return NumenPlugins.aggregatePlanningKnowledge(planning,new PlanningQuery(id,"goal","stage_a",List.of()));
              }
            }
            """);
        String loop=Files.readString(root.resolve("api/common/src/client/java/com/dwinovo/numen/client/agent/EntityAgentLoop.java"));
        StringBuilder methods=new StringBuilder();
        // 055 owner anchor: each slice has one real body now (no delegator); extract that body and
        // keep the real anchor class in this same loader so empty-anchor behaviour is unchanged.
        for(String signature:List.of("private List<ContextBudget.Section> runtimeStateSections(String ownerSection)","private String runtimeStateXml(boolean publish, String origin, int attempt, String ownerSection)","private static ContextBudget.Section runtimeSection(","static ContextBudget.Section rationaleSection(","private static int estimateTokensOfText(","private List<ConvoState.Msg> modelContextSnapshot(")) methods.append(method(loop,signature)).append((char)10);
        int constants=loop.indexOf("    private static final String SECTION_CURRENT_TASK");
        int constantsEnd=loop.indexOf(String.valueOf((char)10).repeat(2),constants);
        String priorities=loop.substring(loop.indexOf("    private static final int PRIORITY_OWNER_REQUEST"),loop.indexOf("    /** 044 §0.7: extractable rationale"));
        sources.put("com.dwinovo.numen.agent.llm.BudgetPolicy","package com.dwinovo.numen.agent.llm; public class BudgetPolicy { public static com.dwinovo.numen.agent.context.ContextBudget budget=com.dwinovo.numen.agent.context.ContextBudget.unlimited(); public static com.dwinovo.numen.agent.context.ContextBudget contextBudget(){return budget;} }");
        sources.put("com.dwinovo.numen.client.agent.RequestProbe", """
            package com.dwinovo.numen.client.agent;
            import java.util.*;
            import com.dwinovo.numen.agent.context.ContextBudget;
            import com.dwinovo.numen.agent.llm.*;
            public class RequestProbe {
              UUID entityUuid; Goal goal=null; Task currentTask=null;
              // Real anchor, same package/loader; empty here so this consumer test's assertions are
              // unchanged. Anchor behaviour itself is asserted by OwnerRequestContinuityTest.
              OwnerRequestAnchor ownerRequestAnchor=OwnerRequestAnchor.none();
              record Goal(String objective){} record Task(String describe){}
              List<com.dwinovo.numen.api.NumenPlugins.DeliveredRef> pendingDeliveredRefs;
              String pendingRuntimeStateXml,pendingDeliveryTaskId,renderedDeliveryTaskId,pendingRuntimeOrigin;
              ContextBudget.Allocation pendingRuntimeStatePlan; int pendingRuntimeAttempt;
              String currentTaskXml(){return "<current_task>CONTROLLED_TASK</current_task>";}
              String inventoryXml(){return "";} String effectsXml(){return "";} String ridingXml(){return "";}
              void maybeSegmentSession(){}
              List<ConvoState.Msg> replayWindow(boolean p){return List.of(new ConvoState.Msg.User("OWNER_REQUEST"));}
            """+loop.substring(constants,constantsEnd)+(char)10+priorities+methods+"""
              public static List<String> capture(boolean crowdedKnowledge) throws Exception {
                if(crowdedKnowledge) com.dwinovo.numen.api.NumenPlugins.contributeExecutorKnowledge((java.util.function.BiFunction<UUID,String,String>)(id,objective)->"K".repeat(com.dwinovo.numen.api.NumenPlugins.MAX_EXECUTOR_KNOWLEDGE_CHARS-28));
                var id=UUID.randomUUID(); String planning=com.dwinovo.numen.plugins.worldstate.RegistrationProbe.register(id);
                var stateField=com.dwinovo.numen.api.NumenPlugins.class.getDeclaredField("STATE");stateField.setAccessible(true);
                Object state=stateField.get(null);var add=state.getClass().getDeclaredMethod("add",String.class,String.class,boolean.class,int.class,java.util.function.Function.class);add.setAccessible(true);
                add.invoke(state,"controlled-safety","environment",true,100,(java.util.function.Function<UUID,String>)uuid -> "<environment>DANGER_MUST_STAY WEATHER_MUST_STAY</environment>");
                RequestProbe probe=new RequestProbe();probe.entityUuid=id;
                String plannerInitial=com.dwinovo.numen.plugins.rdd.PlanningProbe.capture(id);
                var request=probe.modelContextSnapshot(false,"execution",1);
                String initial=((ConvoState.Msg.User)request.getLast()).content(); String kept=probe.pendingRuntimeStatePlan.keptNames().toString();
                long expiry=com.dwinovo.numen.plugins.worldstate.RegistrationProbe.expiry(id);
                com.dwinovo.numen.plugins.worldstate.RegistrationProbe.switchWorld(false);
                String unknown=((ConvoState.Msg.User)probe.modelContextSnapshot(false,"execution",1).getLast()).content();
                com.dwinovo.numen.plugins.worldstate.RegistrationProbe.switchWorld(true);
                long mandatoryChars=probe.pendingRuntimeStatePlan.kept().stream().filter(ContextBudget.Section::mandatory).mapToLong(ContextBudget.Section::chars).sum();
                BudgetPolicy.budget=new ContextBudget(0,0,0,0,mandatoryChars,0,"controlled-char-budget");
                String budgeted=((ConvoState.Msg.User)probe.modelContextSnapshot(false,"execution",1).getLast()).content();
                boolean denied=probe.pendingRuntimeStatePlan.denied();
                BudgetPolicy.budget=ContextBudget.unlimited();
                boolean ttlPreserved=expiry==com.dwinovo.numen.plugins.worldstate.RegistrationProbe.expiry(id);
                com.dwinovo.numen.plugins.worldstate.RegistrationProbe.release(id,false);
                String shared=((ConvoState.Msg.User)probe.modelContextSnapshot(false,"execution",1).getLast()).content();
                String plannerShared=com.dwinovo.numen.plugins.rdd.PlanningProbe.capture(id);
                com.dwinovo.numen.plugins.worldstate.RegistrationProbe.release(id,true);
                String released=((ConvoState.Msg.User)probe.modelContextSnapshot(false,"execution",1).getLast()).content();
                String plannerReleased=com.dwinovo.numen.plugins.rdd.PlanningProbe.capture(id);
                com.dwinovo.numen.plugins.worldstate.RegistrationProbe.register(id);
                com.dwinovo.numen.plugins.worldstate.RegistrationProbe.pressurePlanning();
                String plannerBudgeted=com.dwinovo.numen.plugins.rdd.PlanningProbe.capture(id);
                return List.of(planning,initial,kept,shared,released,budgeted,String.valueOf(denied),unknown,String.valueOf(ttlPreserved),plannerInitial,plannerShared,plannerReleased,plannerBudgeted);
              }
            }
            """);
        String planner=Files.readString(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddStagePlanner.java"));
        String knowledge=Files.readString(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddPlanningKnowledge.java"));
        sources.put("com.dwinovo.numen.plugins.rdd.PlanningProbe", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*; import java.util.function.*;
            class PlanningAssetSnapshot { Map<String,Integer> availableCounts(){return Map.of();} }
            class RddRiskPlanning { static String prepHint(String o,Map<String,Integer> held){return "";} }
            class RddPlanningPolicy { static String block(String o,String stage){return "CONTROLLED_PLANNING_POLICY";} }
            class RddMonitor { static void publish(String event,Map<String,Object> data){} }
            class RddSurvivalDoctrine { static String block(){return "";} }
            class RddPlanningKnowledge {
              interface Provider { String knowledgeFor(UUID id,String objective,String stage,List<String> failures); }
              static final Provider HOST=(id,o,s,f)->com.dwinovo.numen.plugins.worldstate.RegistrationProbe.planning(id);
              static void publish(UUID id,String stage,String knowledge,String request){}
            """+method(knowledge,"static String attach(")+method(knowledge,"static String withKnowledge(")+"""
            }
            public class PlanningProbe {
              static String planningPrompt(String objective,PlanningAssetSnapshot snapshot){return "CONTROLLED_PLANNING_INPUT";}
            """+method(planner,"static void dispatchStages(")+method(planner,"static String withRationale(")+"""
              public static String capture(UUID id){var sent=new ArrayList<String>();dispatchStages(id,"goal",null,new PlanningAssetSnapshot(),"",RddPlanningKnowledge.HOST,()->"",sent::add);if(sent.size()!=1)throw new AssertionError("one planner send");return sent.getFirst();}
            }
            """);
        String cp=System.getProperty("java.class.path");
        var args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-classpath",cp,"-d",temp.toString()));
        for(var entry:sources.entrySet()){var path=temp.resolve(entry.getKey().replace('.','/')+".java");Files.createDirectories(path.getParent());Files.writeString(path,entry.getValue());args.add(path.toString());}
        for(String path:List.of("plugins/worldstate/src/main/java/com/dwinovo/numen/plugins/worldstate/WorldFocusProjector.java","plugins/worldstate/src/main/java/com/dwinovo/numen/plugins/worldstate/focus/FocusPin.java","plugins/worldstate/src/main/java/com/dwinovo/numen/plugins/worldstate/focus/FocusPinStore.java","api/common/src/client/java/com/dwinovo/numen/client/agent/AgentRequestContext.java","api/common/src/client/java/com/dwinovo/numen/client/agent/OwnerRequestAnchor.java")) args.add(root.resolve(path).toString());
        var diagnostics=new java.io.ByteArrayOutputStream();
        int compileExit=ToolProvider.getSystemJavaCompiler().run(null,diagnostics,diagnostics,args.toArray(String[]::new));
        assertEquals(0,compileExit,()->"SETUP: production request slices compile: "+diagnostics.toString(java.nio.charset.StandardCharsets.UTF_8));
        var urls=new ArrayList<URL>();urls.add(temp.toUri().toURL());
        for(String part:cp.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator)))if(!part.isBlank())urls.add(Path.of(part).toUri().toURL());
        if (!crowdedOnly) try(var loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader())){
            @SuppressWarnings("unchecked") var captured=(List<String>)loader.loadClass("com.dwinovo.numen.client.agent.RequestProbe").getMethod("capture",boolean.class).invoke(null,false);
            assertAll("captured execution request controls",
            () -> assertTrue(captured.get(0).contains("FOCUS_VISIBLE"),"actual planning registration callback"),
            () -> assertTrue(captured.get(1).contains("FOCUS_VISIBLE"),captured.toString()),
            () -> assertTrue(captured.get(1).contains("OWNER_REQUEST")&&captured.get(1).contains("<runtime_state"),captured.toString()),
            () -> assertTrue(captured.get(2).contains("executor_knowledge"),captured.toString()),
            () -> assertTrue(captured.get(3).contains("FOCUS_VISIBLE"),"remaining owner still reaches next actual request"),
            () -> assertFalse(captured.get(4).contains("FOCUS_VISIBLE"),"last release removes focus from next actual request"),
            () -> assertTrue(captured.get(4).contains("CONTROLLED_TASK"),"release does not remove current task"),
            () -> assertFalse(captured.get(5).contains("FOCUS_VISIBLE"),"optional focus must not consume mandatory budget"),
            () -> assertTrue(captured.get(5).contains("DANGER_MUST_STAY") && captured.get(5).contains("WEATHER_MUST_STAY") && captured.get(5).contains("CONTROLLED_TASK"),captured.get(5)),
            () -> assertEquals("false",captured.get(6),"mandatory-only budget fits; optional focus is dropped, not mandatory content"),
            () -> assertFalse(captured.get(7).contains("FOCUS_VISIBLE"),"unknown world cannot borrow recorded focus identity"),
            () -> assertEquals("true",captured.get(8),"request building cannot renew the focus TTL"),
            () -> assertTrue(captured.get(9).contains("FOCUS_VISIBLE"),"planning dispatch receives registered focus"),
            () -> assertTrue(captured.get(10).contains("FOCUS_VISIBLE"),"other owner remains in next planning request"),
            () -> assertFalse(captured.get(11).contains("FOCUS_VISIBLE"),"last release clears next planning request"),
            () -> assertFalse(captured.get(12).contains("<focus>"),"planning knowledge cap rejects focus whole"),
            () -> assertTrue(captured.get(12).contains("CONTROLLED_PLANNING_POLICY") && captured.get(12).contains("CONTROLLED_PLANNING_INPUT"),"planning knowledge cannot displace base policy or input"));
        }
        // Separate loader = separate real contributor registry. Exercise the upstream knowledge
        // cap before the actual runtime builder, not a hand-built truncated focus string.
        if (crowdedOnly) try(var loader=new URLClassLoader(urls.toArray(URL[]::new),ClassLoader.getPlatformClassLoader())){
            @SuppressWarnings("unchecked") var crowded=(List<String>)loader.loadClass("com.dwinovo.numen.client.agent.RequestProbe").getMethod("capture",boolean.class).invoke(null,true);
            String sent=crowded.get(1);
            assertAll("upstream cap request controls",
                () -> assertTrue(!sent.contains("<focus>") || sent.contains("</focus>"),
                    "upstream knowledge cap must not deliver a partial focus through the actual request builder: "+sent),
                () -> assertTrue(sent.contains("DANGER_MUST_STAY") && sent.contains("WEATHER_MUST_STAY"),
                    "upstream knowledge pressure cannot displace mandatory safety"));
        }
    }
    private static String method(String source,String signature){int start=source.indexOf(signature);assertTrue(start>=0,signature);int open=source.indexOf('{',start),depth=1;for(int i=open+1;i<source.length();i++){if(source.charAt(i)=='{')depth++;else if(source.charAt(i)=='}'&&--depth==0)return source.substring(start,i+1);}throw new AssertionError(signature);}
}
