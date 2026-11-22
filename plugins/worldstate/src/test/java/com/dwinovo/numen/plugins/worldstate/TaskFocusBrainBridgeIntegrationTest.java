package com.dwinovo.numen.plugins.worldstate;

import org.junit.jupiter.api.Test;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

/** Offline integration compiles the real brain, bridge and store with boundary stubs.
 * Calls reconcile and rememberFinished directly; does not test Minecraft scheduling or TaskSlot callbacks. */
class TaskFocusBrainBridgeIntegrationTest {
    @Test void brainToRealStore() throws Exception { runFixture(); }
    public static void main(String[] args) throws Exception { runFixture(); }

    static void runFixture() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("plugins/worldstate/src/main/java/com/dwinovo/numen/plugins/worldstate/TaskFocusLeaseBridge.java"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("repository root not found");
        Path tmp = Files.createTempDirectory("focus-bridge-fixture-");
        try {
            Map<String,String> sources = new LinkedHashMap<>();
            sources.put("net.minecraft.server.MinecraftServer", """
                package net.minecraft.server;
                public class MinecraftServer { public boolean isSameThread(){return true;} }
                """);
            sources.put("com.dwinovo.numen.entity.NumenPlayer", """
                package com.dwinovo.numen.entity;
                public class NumenPlayer {
                  public final java.util.UUID id; public final net.minecraft.server.MinecraftServer server;
                  public NumenPlayer(java.util.UUID i, net.minecraft.server.MinecraftServer s){id=i;server=s;}
                  public boolean isRemoved(){return false;} public void resumeAllReflexes(){} public long getGameTime(){return 0;} public net.minecraft.server.level.ServerPlayer resolveOwnerPlayer(){return null;} public NumenPlayer level(){return this;} public NumenPlayer serverLevel(){return this;}
                  public net.minecraft.server.MinecraftServer getServer(){return server;}
                  public java.util.UUID getUUID(){return id;}
                  public NumenPlayer dimension(){return this;} public String location(){return "minecraft:overworld";}
                }
                """);
            sources.put("com.dwinovo.numen.api.WorldContextStamp", """
                package com.dwinovo.numen.api;
                public record WorldContextStamp(java.util.Map<String,String> identity) {
                  public static WorldContextStamp current;
                  public static WorldContextStamp capture(){return current;}
                  public static WorldContextStamp capture(net.minecraft.server.MinecraftServer s){return FixtureStamps.forServer(s);}
                }
                """);
            sources.put("com.dwinovo.numen.api.FixtureStamps", """
                package com.dwinovo.numen.api;
                public class FixtureStamps { public static final java.util.Map<net.minecraft.server.MinecraftServer,WorldContextStamp> stamps=new java.util.IdentityHashMap<>();
                  public static WorldContextStamp forServer(net.minecraft.server.MinecraftServer s){return stamps.get(s);} }
                """);
            sources.put("com.dwinovo.numen.api.NumenPlugins", """
                package com.dwinovo.numen.api;
                public class NumenPlugins { public static Object resolver, leaseService; public static <T> T capability(Class<T> c){return c.cast(c.getSimpleName().equals("TaskFocusLeaseService") ? leaseService : resolver);} }
                """);
            sources.put("com.dwinovo.numen.task.TaskState", "package com.dwinovo.numen.task; public enum TaskState { RUNNING, DONE, SUCCESS, TIMEOUT, CANCELLED }");
            sources.put("com.dwinovo.numen.task.TaskRecord", """
                package com.dwinovo.numen.task;
                import com.dwinovo.numen.api.WorldContextStamp;
                public class TaskRecord {
                  public record Selection(String companionId,String skillRef,String version,String bodyDigest,WorldContextStamp token){}
                  public Selection selection; public WorldContextStamp stamp;
                  public TaskState state=TaskState.RUNNING; public TaskState getState(){return state;} public String publicId(){return "task";} public String getToolName(){return "test";} public String describe(){return "test";} public long getStartedGameTime(){return 0;} public long getDeadlineGameTime(){return 0;} public static final long NO_DEADLINE=Long.MAX_VALUE; public TaskResult getResult(){return null;} public boolean isAsync(){return false;} public boolean isExternalCall(){return false;} public String getToolCallId(){return "call";}
                  public Selection skillSelection(){return selection;} public WorldContextStamp identityToken(){return stamp;}
                  public static boolean completeIdentity(WorldContextStamp s){return s!=null && s.identity().containsKey("world_id") && s.identity().containsKey("server_session");}
                }
                """);
            sources.put("com.dwinovo.numen.plugins.worldstate.WorldStateService", """
                package com.dwinovo.numen.plugins.worldstate;
                public class WorldStateService { private final com.dwinovo.numen.plugins.worldstate.focus.FocusPinStore pins=new com.dwinovo.numen.plugins.worldstate.focus.FocusPinStore();
                  public com.dwinovo.numen.plugins.worldstate.focus.FocusPinStore focusPins(){return pins;} }
                """);
            sources.put("com.dwinovo.numen.plugins.worldstate.WorldStateMod", """
                package com.dwinovo.numen.plugins.worldstate;
                public class WorldStateMod { public static net.minecraft.server.MinecraftServer active;
                  public static boolean isActiveServer(net.minecraft.server.MinecraftServer s){return s!=null && s==active;} }
                """);
            sources.put("com.dwinovo.numen.plugins.worldstate.Fixture", """
                package com.dwinovo.numen.plugins.worldstate;
                import com.dwinovo.numen.api.*;
                import com.dwinovo.numen.entity.NumenPlayer;
                import com.dwinovo.numen.task.TaskRecord;
                import net.minecraft.server.MinecraftServer;
                import java.util.*;
                import java.security.MessageDigest;
                import java.nio.charset.StandardCharsets;
                public class Fixture {
                  static void check(boolean b,String message){if(!b) throw new AssertionError(message);}
                  public static void run() throws Exception {
                    var id=UUID.randomUUID(); var oldServer=new MinecraftServer(); var newServer=new MinecraftServer();
                    var oldStamp=new WorldContextStamp(Map.of("world_id","world","server_session","old"));
                    var newStamp=new WorldContextStamp(Map.of("world_id","world","server_session","new"));
                    FixtureStamps.stamps.put(oldServer,oldStamp); FixtureStamps.stamps.put(newServer,newStamp);
                    var oldBody=new NumenPlayer(id,oldServer); var body=new NumenPlayer(id,newServer);
                    String text="approved body"; String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
                    class Resolver implements LearnedSkillBodyResolver {
                      String bodyRef="skill:demo@v1", version="v1"; int resolutions;
                      public Resolution resolve(UUID c,String r,View v){check(c.equals(id)&&v==View.EXECUTOR,"body lookup identity/view"); resolutions++; return new Resolution(bodyRef,text,"");}
                      public FocusDeclaration resolveFocusDeclaration(UUID c,String r,View v){check(c.equals(id)&&v==View.EXECUTOR,"declaration identity/view"); return new FocusDeclaration(r,"environment",version,"");}
                    }
                    var resolver=new Resolver(); NumenPlugins.resolver=resolver;
                    var storeHost=new WorldStateService(); var bridge=new TaskFocusLeaseBridge(storeHost);
                    var task=new TaskRecord(); task.stamp=newStamp; task.selection=new TaskRecord.Selection(id.toString(),"skill:demo","v1",digest,newStamp);
                    WorldStateMod.active=newServer; WorldContextStamp.current=newStamp;
                    NumenPlugins.leaseService=bridge; task.selection=null; com.dwinovo.numen.task.BrainFixture.start(id,body,task); check(storeHost.focusPins().active(id,"world","new",System.currentTimeMillis()).isEmpty(),"no early pin"); task.selection=new TaskRecord.Selection(id.toString(),"skill:demo","v1",digest,newStamp); com.dwinovo.numen.task.BrainFixture.reconcile(body,task);
                    var pins=storeHost.focusPins(); check(pins.active(id,"world","new",System.currentTimeMillis()).size()==1,"real store must contain pin"); check(resolver.resolutions==1,"reconcile must acquire only once");
                    task.state=com.dwinovo.numen.task.TaskState.SUCCESS; com.dwinovo.numen.task.BrainFixture.finish(task); check(pins.active(id,"world","new",System.currentTimeMillis()).isEmpty(),"brain finish releases real pin");
                  }
                }
                """);
            addBrainStubs(sources);
            List<String> args = new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",tmp.toString()));
            for (var entry : sources.entrySet()) {
                Path file=tmp.resolve(entry.getKey().replace('.', '/')+".java"); Files.createDirectories(file.getParent()); Files.writeString(file,entry.getValue()); args.add(file.toString());
            }
            Path plugin=root.resolve("plugins/worldstate/src/main/java/com/dwinovo/numen/plugins/worldstate");
            for (String source : List.of("TaskFocusLeaseBridge.java","focus/FocusPin.java","focus/FocusPinStore.java")) args.add(plugin.resolve(source).toString());
            Path api=root.resolve("api/common/src/main/java/com/dwinovo/numen/api");
            for (String source : List.of("TaskFocusLeaseService.java","LearnedSkillBodyResolver.java")) args.add(api.resolve(source).toString());
            args.add(root.resolve("api/common/src/main/java/com/dwinovo/numen/task/CompanionBrain.java").toString());
            var compiler=ToolProvider.getSystemJavaCompiler();
            if (compiler==null) throw new AssertionError("JDK compiler required");
            if (compiler.run(null,null,null,args.toArray(String[]::new))!=0) throw new AssertionError("fixture compilation failed");
            // Isolate all tested classes so existing build outputs cannot shadow fresh sources.
            try (var loader=new URLClassLoader(new URL[]{tmp.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
                Class<?> fixture=loader.loadClass("com.dwinovo.numen.plugins.worldstate.Fixture");
                fixture.getMethod("run").invoke(null);
            }
        } finally {
            try (var paths=Files.walk(tmp)) { for (Path p:paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p); }
        }
    }
    static void addBrainStubs(Map<String,String> s) {
        s.put("com.dwinovo.numen.task.BrainFixture", """
            package com.dwinovo.numen.task;
            public class BrainFixture {
              static CompanionBrain brain;
              public static void start(java.util.UUID id,com.dwinovo.numen.entity.NumenPlayer body,TaskRecord rec){brain=new CompanionBrain(id); brain.reconcileTaskFocus(body,rec);}
              public static void reconcile(com.dwinovo.numen.entity.NumenPlayer body,TaskRecord rec){brain.reconcileTaskFocus(body,rec); brain.reconcileTaskFocus(body,rec);}
              public static void finish(TaskRecord rec){brain.rememberFinished(rec);}
            }
            """);
        s.put("com.dwinovo.numen.task.Task", "package com.dwinovo.numen.task; public interface Task { enum StopReason { PREEMPTED } boolean canRun(com.dwinovo.numen.entity.NumenPlayer p); TaskState tick(com.dwinovo.numen.entity.NumenPlayer p); void stop(com.dwinovo.numen.entity.NumenPlayer p, StopReason r); String name(); }");
        s.put("com.dwinovo.numen.task.TaskSlot", """
            package com.dwinovo.numen.task; public class TaskSlot {
              public TaskSlot(java.util.function.Consumer<TaskRecord> c){} public void onSettled(java.util.function.Consumer<TaskRecord> c){} public TaskRecord record(){return null;} public boolean isEmpty(){return true;} public boolean canRun(com.dwinovo.numen.entity.NumenPlayer p){return false;} public void tick(com.dwinovo.numen.entity.NumenPlayer p){} public void freeze(){} public void settleIfTerminal(){} public void dropNoResult(com.dwinovo.numen.entity.NumenPlayer p){} public void finalizeInline(){} public void loseBody(com.dwinovo.numen.entity.NumenPlayer p){}
            }
            """);
        s.put("com.dwinovo.numen.task.TaskTerminalRegistry", "package com.dwinovo.numen.task; public class TaskTerminalRegistry { public static void remember(java.util.UUID id,TaskRecord r){} public static TaskRecord find(java.util.UUID id,String s){return null;} }");
        s.put("com.dwinovo.numen.task.HandPinRelease", "package com.dwinovo.numen.task; public class HandPinRelease { public HandPinRelease(int n){} public boolean tick(boolean b){return false;} }");
        s.put("com.dwinovo.numen.task.BrainChains", "package com.dwinovo.numen.task; public class BrainChains { public static java.util.List<Task> build(){return java.util.List.of();} }");
        s.put("com.dwinovo.numen.task.chain.SpeakingLookChain", "package com.dwinovo.numen.task.chain; public class SpeakingLookChain implements com.dwinovo.numen.task.Task { public boolean canRun(com.dwinovo.numen.entity.NumenPlayer p){return false;} public com.dwinovo.numen.task.TaskState tick(com.dwinovo.numen.entity.NumenPlayer p){return com.dwinovo.numen.task.TaskState.RUNNING;} public void stop(com.dwinovo.numen.entity.NumenPlayer p, StopReason r){} public String name(){return null;} }");
        s.put("com.dwinovo.numen.task.TaskSelector", "package com.dwinovo.numen.task; public class TaskSelector { public static Task select(java.util.List<Task> a,Task b,Task c,java.util.List<Task> d,com.dwinovo.numen.entity.NumenPlayer p){return null;} }");
        s.put("com.dwinovo.numen.task.TaskSessionHooks", "package com.dwinovo.numen.task; public class TaskSessionHooks { public static void fireSessionEnd(com.dwinovo.numen.entity.NumenPlayer p){} }");
        s.put("com.dwinovo.numen.task.TaskPersistence", "package com.dwinovo.numen.task; public class TaskPersistence { public static void forget(com.dwinovo.numen.entity.NumenPlayer p){} }");
        s.put("com.dwinovo.numen.task.TaskResult", "package com.dwinovo.numen.task; public class TaskResult { public String message(){return null;} public String toJson(){return null;} public java.util.Map<String,Object> data(){return null;} }");
        s.put("com.dwinovo.numen.task.TaskCompletionText", "package com.dwinovo.numen.task; public class TaskCompletionText { public static String withStopHint(String s){return s;} }");
        s.put("org.slf4j.LoggerFactory", "package org.slf4j; public class LoggerFactory { public static Logger getLogger(Class<?> c){return new Logger();} public static class Logger { public void warn(String s,Object... a){} public void info(String s,Object... a){} } }");
        s.put("com.dwinovo.numen.Constants", "package com.dwinovo.numen; public class Constants { public static final org.slf4j.LoggerFactory.Logger LOG=new org.slf4j.LoggerFactory.Logger(); }");
        s.put("com.dwinovo.numen.api.CompanionEvent", "package com.dwinovo.numen.api; public class CompanionEvent { public static final Object TASK_FINISHED=new Object(); public record TaskFinished(java.util.UUID id,String task,String tool,boolean ok,String state){} }");
        s.put("com.dwinovo.numen.entity.CompanionEvents", "package com.dwinovo.numen.entity; public class CompanionEvents { public static void fire(Object a,Object b){} }");
        s.put("net.minecraft.server.level.ServerPlayer", "package net.minecraft.server.level; public class ServerPlayer {} ");
        s.put("com.dwinovo.numen.network.payload.CurrentTaskPayload", "package com.dwinovo.numen.network.payload; public class CurrentTaskPayload { public CurrentTaskPayload(java.util.UUID id,String task,String tool,String desc,boolean deadline,long elapsed){} public static CurrentTaskPayload idle(java.util.UUID id){return null;} }");
        s.put("com.dwinovo.numen.network.payload.TaskResultPayload", "package com.dwinovo.numen.network.payload; public class TaskResultPayload { public TaskResultPayload(java.util.UUID id,String call,String json){} }");
        s.put("com.dwinovo.numen.platform.Services", "package com.dwinovo.numen.platform; public class Services { public static final Network NETWORK=new Network(); public static class Network { public void sendToPlayer(net.minecraft.server.level.ServerPlayer p,Object msg){} } }");
        s.put("com.dwinovo.numen.event.NumenEvents", "package com.dwinovo.numen.event; public class NumenEvents { public static void taskFinished(com.dwinovo.numen.entity.NumenPlayer p,String id,String tool,String status,String msg){} }");
        s.put("com.dwinovo.numen.monitor.MonitoringJournal", "package com.dwinovo.numen.monitor; public class MonitoringJournal { public static MonitoringJournal get(){return new MonitoringJournal();} public void publish(String a,String b,java.util.Map<String,Object> c,Object d){} }");
    }

}