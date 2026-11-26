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

/** Staged controlled receiver: original Detector continuous admission-to-body region,
 * original dispatcher, TaskDispatch and TaskSlot. World/tool/TaskFactory effects are ports.
 * A seated inert task is NOT resumed mine/craft execution. Sleep-cycle integration follows this shell. */
class RddBodyResumeAdmissionConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;static Class<?> host;
    static String method(String s,String p){int a=s.indexOf(p);assertTrue(a>=0,"SETUP missing "+p);int b=s.indexOf("\n    }",s.indexOf('{',a));assertTrue(b>a);return s.substring(a,b+6);}
    static void add(List<String> args,String name,String text)throws Exception{Path p=temp.resolve(name.replace('.','/')+".java");Files.createDirectories(p.getParent());Files.writeString(p,text);args.add(p.toString());}
    @BeforeAll static void compile()throws Exception{
        Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.isDirectory(root.resolve("plugins/rdd/src/main/java")))root=root.getParent();assertNotNull(root);
        Path prod=root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd"),api=root.resolve("api/common/src/main/java/com/dwinovo/numen");
        String detector=Files.readString(prod.resolve("RddDetector.java"));
        int begin=detector.indexOf("            Map<String, Integer> countsAtAdmission = counts;"),end=detector.indexOf("            if (conditionMatches(ap, current, counts)) {",detector.indexOf("RddBodyDispatcher.maybeSubmit(ap, current, chain);",begin));
        assertTrue(begin>=0&&end>begin,"SETUP original continuous Detector region");
        String slice=detector.substring(begin,end);assertTrue(slice.contains("EarlyAchievement")&&slice.contains("stopAfterObservation")&&slice.contains("kickoffNudge")&&slice.contains("stallWatcher.track"));
        String plugin=Files.readString(prod.resolve("RddPlugin.java"));
        String state=plugin.substring(plugin.indexOf("    record BodyState("),plugin.indexOf("\n    }",plugin.indexOf("    record BodyState("))+6);
        String bodyMethods="";for(String p:List.of("    public static BodyState bodyState(","    public static void rememberBody(","    static void rememberYieldBody(","    public static void clearBody(","    public static String nextBodyCallId("))bodyMethods+=method(plugin,p)+"\n";
        String sideSource=Files.readString(prod.resolve("SideTaskHost.java")),sideMethods="";
        for(String prefix:List.of("    private static MainlineResumeToken currentMainlineToken(","    private static MainlineResumeToken validateMainline(","    static SideTask activeSleepOwner(","    static com.dwinovo.numen.rdd.side.SleepCycleEvidence readSleepCycle(","    static void clearTerminalSleepCycle("))sideMethods+=method(sideSource,prefix)+"\n";
        int interruptBegin=sideSource.indexOf("                // Same server thread: stop ONLY"),interruptEnd=sideSource.indexOf("            });",interruptBegin);
        assertTrue(interruptBegin>=0&&interruptEnd>interruptBegin);
        String callback=sideSource.substring(interruptBegin,interruptEnd);
        String safety="";for(String prefix:List.of("        static boolean mayInterrupt(","        static void tick(SideTaskEngine engine,")){int a=sideSource.indexOf(prefix),b=sideSource.indexOf("\n        }",a);assertTrue(a>=0&&b>a);safety+=sideSource.substring(a,b+10);}
        String apiSource=Files.readString(api.resolve("api/NumenPlugins.java"));int apiAt=apiSource.indexOf("        public boolean interruptForSleep(");int apiEnd=apiSource.indexOf("\n        }",apiAt);assertTrue(apiAt>=0&&apiEnd>apiAt);
        String apiInterrupt=apiSource.substring(apiAt,apiEnd+10).replace("public boolean", "public static boolean");
        String stop=method(Files.readString(api.resolve("task/CompanionTickDispatcher.java")),"    public static TaskRecord stopActive(");
        String fixture=Files.readString(root.resolve("plugins/rdd/src/test/resources/body-resume/RddBodyResumeAdmissionHost.java.in"));
        fixture=fixture.replace("// @SIDE_METHODS@",sideMethods.replace("private static", "static")).replace("// @INTERRUPT_CALLBACK@",callback).replace("// @SLEEP_SAFETY@",safety);
        fixture=fixture.replace("// @DETECTOR_REGION@",slice).replace("// @BODY_STATE@",state+bodyMethods);
        String cp=System.getProperty("java.class.path")+File.pathSeparator+Path.of(TaskChain.class.getProtectionDomain().getCodeSource().getLocation().toURI())+File.pathSeparator+Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-classpath",cp,"-d",temp.toString()));
        add(args,"com.dwinovo.numen.plugins.rdd.RddBodyResumeAdmissionHost",fixture);
        for(String f:List.of("RddMainlineAdmission","RddBodyDispatcher","RddBodyTools","SleepWakeWatcher"))args.add(prod.resolve(f+".java").toString());
        for(String f:List.of("TaskDispatch","TaskSlot","TaskRecord","TaskState","TaskResult","TaskOwnership","Task","TaskSessionHooks"))args.add(api.resolve("task/"+f+".java").toString());
        args.add(api.resolve("agent/tool/api/ToolContext.java").toString());
        String player=Files.readString(root.resolve("plugins/rdd/src/test/resources/sleep-cycle/SleepCyclePlayer.java.in"))
            .replace("public final Level world=new Level();","public final Level world=new Level();")
            .replace("public NumenPlayer(UUID id,MinecraftServer server){this.id=id;this.server=server;}","public NumenPlayer(UUID id,MinecraftServer server){this.id=id;this.server=server;world.server=server;}")
            .replace("public static class Level{", "public static class Level{public MinecraftServer server;public MinecraftServer getServer(){return server;}")
            .replace("public UUID getUUID()", "public int hurtTime;public boolean isDeadOrDying(){return !alive;}public Object resolveOwnerPlayer(){return this;}public Object lastAttacker(){return null;}public UUID getUUID()");
        add(args,"com.dwinovo.numen.entity.NumenPlayer",player);
        add(args,"net.minecraft.resources.ResourceLocation","package net.minecraft.resources;public class ResourceLocation{public static ResourceLocation tryParse(String id){throw new AssertionError(\"unused block-registry port\");}}");
        add(args,"net.minecraft.core.registries.BuiltInRegistries","package net.minecraft.core.registries;public class BuiltInRegistries{public static final Registry BLOCK=new Registry();public static class Registry{public boolean containsKey(net.minecraft.resources.ResourceLocation id){throw new AssertionError(\"unused block-registry port\");}}}");
        add(args,"net.minecraft.server.level.ServerPlayer","package net.minecraft.server.level;public class ServerPlayer{}");
        add(args,"net.minecraft.server.MinecraftServer","package net.minecraft.server;public class MinecraftServer{public java.util.List<net.minecraft.server.level.ServerPlayer> players=new java.util.ArrayList<>();public PlayerList getPlayerList(){return new PlayerList();}public class PlayerList{public java.util.List<net.minecraft.server.level.ServerPlayer> getPlayers(){return players;}}public boolean isSameThread(){return true;}}");
        add(args,"net.neoforged.neoforge.common.NeoForge","package net.neoforged.neoforge.common;public class NeoForge{public static final Bus EVENT_BUS=new Bus();public static class Bus{public <T>void addListener(java.util.function.Consumer<T> c){}}}");
        add(args,"net.neoforged.neoforge.event.tick.ServerTickEvent","package net.neoforged.neoforge.event.tick;public class ServerTickEvent{public record Pre(net.minecraft.server.MinecraftServer server){public net.minecraft.server.MinecraftServer getServer(){return server;}}}");
        add(args,"com.dwinovo.numen.api.WorldContextStamp","package com.dwinovo.numen.api;import java.util.*;public class WorldContextStamp{public record Token(Map<String,String> identity,long epoch){}public static Token capture(net.minecraft.server.MinecraftServer s){return new Token(Map.of(\"world_id\",\"fixture\",\"server_session\",\"one\"),1);}}");
        add(args,"com.dwinovo.numen.task.CompanionTickDispatcher",SLOT_PORT.replace("// @REAL_STOP@",stop));
        add(args,"com.dwinovo.numen.api.NumenPlugins","package com.dwinovo.numen.api;public class NumenPlugins{"+apiInterrupt+"}");
        add(args,"com.dwinovo.numen.api.NumenApi","package com.dwinovo.numen.api;public interface NumenApi{String SLEEP_INTERRUPT=\"sleep_interrupt\";}");
        add(args,"com.dwinovo.numen.network.payload.NumenEventPayload","package com.dwinovo.numen.network.payload;public record NumenEventPayload(java.util.UUID id,String event,String task,long time,boolean completed){}");
        add(args,"com.dwinovo.numen.platform.Services","package com.dwinovo.numen.platform;public class Services{public static Runnable onSend=()->{};public static final Network NETWORK=new Network();public static class Network{public void sendToPlayer(Object owner,Object payload){com.dwinovo.numen.task.CompanionTickDispatcher.trace.add(\"invalidate\");onSend.run();}}}");
        add(args,"com.dwinovo.numen.task.TaskFactory",FACTORY_PORT);
        add(args,"com.dwinovo.numen.task.TaskPersistence","package com.dwinovo.numen.task;public class TaskPersistence{public static void forget(com.dwinovo.numen.entity.NumenPlayer p){}public static void remember(com.dwinovo.numen.entity.NumenPlayer p,String s,com.google.gson.JsonObject a){}}");
        add(args,"com.dwinovo.numen.event.NumenEvents","package com.dwinovo.numen.event;public class NumenEvents{public static void taskFinished(com.dwinovo.numen.entity.NumenPlayer p,String a,String b,String c,String d){}}");
        add(args,"com.dwinovo.numen.agent.tool.NumenTool","package com.dwinovo.numen.agent.tool;public interface NumenTool{void onServerCall(String id,com.google.gson.JsonObject args,com.dwinovo.numen.entity.NumenPlayer p,java.util.function.Consumer<String> reply);}");
        add(args,"com.dwinovo.numen.agent.tool.TrustedYieldTool","package com.dwinovo.numen.agent.tool;public interface TrustedYieldTool extends NumenTool{Object captureHostBinding(com.dwinovo.numen.entity.NumenPlayer p);void onTrustedHostCall(String id,com.google.gson.JsonObject args,com.dwinovo.numen.entity.NumenPlayer p,Object binding,java.util.function.Consumer<String> reply);}");
        add(args,"com.dwinovo.numen.agent.tool.ToolRegistry","package com.dwinovo.numen.agent.tool;public class ToolRegistry{public static NumenTool tool;public static NumenTool resolve(String name){return tool;}}");
        add(args,"org.slf4j.Logger","package org.slf4j;public interface Logger{default void info(String s,Object...a){}default void warn(String s,Object...a){}default void error(String s,Object...a){}}");
        add(args,"org.slf4j.LoggerFactory","package org.slf4j;public class LoggerFactory{public static Logger getLogger(Class<?> c){return new Logger(){};}}");
        ByteArrayOutputStream err=new ByteArrayOutputStream();assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,err,err,args.toArray(String[]::new)),"SETUP: "+err);
        loader=new URLClassLoader(new URL[]{temp.toUri().toURL()},RddBodyResumeAdmissionConsumerTest.class.getClassLoader()){
            protected Class<?> loadClass(String n,boolean resolve)throws ClassNotFoundException{if(n.startsWith("com.dwinovo.numen.plugins.rdd.")||n.startsWith("com.dwinovo.numen.task.")||n.startsWith("com.dwinovo.numen.entity.")||n.startsWith("com.dwinovo.numen.api.")||n.startsWith("com.dwinovo.numen.agent.tool.")||n.startsWith("net.minecraft.")||n.startsWith("com.dwinovo.numen.event.")||n.startsWith("com.dwinovo.numen.platform.")||n.startsWith("com.dwinovo.numen.network.payload.")||n.startsWith("org.slf4j.")){try{Class<?> c=findClass(n);if(resolve)resolveClass(c);return c;}catch(ClassNotFoundException absent){}}return super.loadClass(n,resolve);}};
        host=loader.loadClass("com.dwinovo.numen.plugins.rdd.RddBodyResumeAdmissionHost");
    }
    @AfterAll static void close()throws Exception{if(loader!=null)loader.close();}
    @SuppressWarnings("unchecked") static Map<String,Object> run(String s)throws Exception{return (Map<String,Object>)host.getMethod("run",String.class).invoke(null,s);}
    @Test void originalDetectorRegionSeatsAndStartsExactlyOnce()throws Exception{var r=run("ready");assertEquals(1,r.get("starts"));assertEquals(true,r.get("sameRecord"));assertEquals(true,r.get("identity"));assertEquals(true,r.get("reply"));assertEquals(true,r.get("callId"));}
    @Test void activeSideGateReturnsBeforeAnyToolOrSlotAdmission()throws Exception{var r=run("side");assertEquals(0,r.get("starts"));assertEquals(false,r.get("sameRecord"));}
    @Test void originalBodySubmissionOffGateDoesNotSeatATask()throws Exception{var r=run("off");assertEquals(0,r.get("starts"));assertEquals(false,r.get("sameRecord"));}
    @Test void matchedSleepCycleLicensesTheNextOriginalDetectorTickToSeatOnce()throws Exception{var r=run("cycle");assertEquals(0,r.get("before"));assertEquals(1,r.get("completed"));assertEquals(1,r.get("resumed"));assertEquals(1,r.get("starts"));assertEquals(true,r.get("sameRecord"));assertEquals(true,r.get("stableOwner"));}
    @Test void bindingAndSleepingWithoutWakeDoNotSeatTheMainline()throws Exception{var r=run("unmatched");assertEquals(0,r.get("starts"));assertEquals(0,r.get("completed"));assertEquals(0,r.get("resumed"));}
    @Test void oldCycleCannotLicenseOrUnparkAReplacementChain()throws Exception{var r=run("replacement");assertEquals(0,r.get("starts"));assertEquals(0,r.get("resumed"));assertEquals(true,r.get("replacementParked"));}
    @Test void actualSleepInterruptStopsOldSlotThenOriginalDetectorAcceptsNewRecordOnce()throws Exception{var r=run("handoff");assertEquals(1,r.get("before"),"old task only before matched wake");assertEquals(1,r.get("completed"));assertEquals(1,r.get("resumed"));assertEquals(1,r.get("stopped"));assertEquals(true,r.get("bodyCleared"));assertEquals(true,r.get("order"));assertEquals(true,r.get("oldToNew"));assertEquals(2,r.get("starts"));assertEquals(true,r.get("stableOwner"));}
    @Test void lateOriginalCallbackCannotStopAReplacementSlot()throws Exception{var r=run("lateSlot");assertEquals(false,r.get("callback"));assertEquals(true,r.get("replacementKept"));assertEquals(true,r.get("newBodyKept"));}
    @Test void capturedBodyStateReferenceCannotClearANewerBodyState()throws Exception{var r=run("lateState");assertEquals(true,r.get("callback"));assertEquals(true,r.get("newBodyKept"));}
    @Test void clearRiskDoesNotInterruptNormallyBusyWork()throws Exception{var r=run("clearRisk");assertEquals(0,r.get("stopped"));assertEquals(true,r.get("replacementKept"));}
    static final String SLOT_PORT="""
        package com.dwinovo.numen.task;import java.util.*;import com.dwinovo.numen.entity.NumenPlayer;
        public class CompanionTickDispatcher{static final Map<UUID,TaskSlot> slots=new HashMap<>(),sync=new HashMap<>();
            public static final List<String> trace=new ArrayList<>();static final Map<UUID,CompanionBrain> BRAINS=new HashMap<>();static class CompanionBrain{final TaskSlot current;CompanionBrain(TaskSlot s){current=s;}}
            static TaskSlot currentSlotFor(UUID id){TaskSlot s=slots.computeIfAbsent(id,k->new TaskSlot(r->trace.add("settle")));BRAINS.putIfAbsent(id,new CompanionBrain(s));return s;}static TaskSlot syncSlotFor(UUID id){return sync.computeIfAbsent(id,k->new TaskSlot(r->{}));}
            public static TaskRecord currentTaskFor(UUID id){return currentSlotFor(id).record();}static boolean currentFreshlyAccepted(NumenPlayer p){return currentSlotFor(p.getUUID()).freshlyAccepted(p);}
            // @REAL_STOP@
            public static void clear(){slots.clear();sync.clear();BRAINS.clear();trace.clear();}}
        """;
    static final String FACTORY_PORT="""
        package com.dwinovo.numen.task;import com.dwinovo.numen.entity.NumenPlayer;
        public class TaskFactory{public static int starts;static Task create(NumenPlayer p,TaskRecord r){return new Task(){public void start(NumenPlayer p){starts++;}public TaskState tick(NumenPlayer p){return TaskState.RUNNING;}public void stop(NumenPlayer p,StopReason s){}public TaskResult result(TaskState s){return TaskResult.ok("controlled terminal");}public String name(){return r.getToolName();}};}}
        """;
}
