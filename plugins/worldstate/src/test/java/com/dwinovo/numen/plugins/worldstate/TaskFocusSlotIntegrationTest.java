package com.dwinovo.numen.plugins.worldstate;

import org.junit.jupiter.api.Test;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

/** Offline integration compiles the real brain, bridge and store with boundary stubs.
 * Uses the real TaskSlot callback; does not test Minecraft scheduling. */
class TaskFocusSlotIntegrationTest {
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
                  public void setState(TaskState s){state=s;} public void bindIdentity(WorldContextStamp s){stamp=s;} public void markStarted(long t){} public boolean acceptedWithin(long t,long n){return false;} public void extendDeadlineTo(long t){} public void setResult(TaskResult r){} public TaskState state=TaskState.RUNNING; public TaskState getState(){return state;} public String publicId(){return "task";} public String getToolName(){return "test";} public String describe(){return "test";} public long getStartedGameTime(){return 0;} public long getDeadlineGameTime(){return 0;} public static final long NO_DEADLINE=Long.MAX_VALUE; public TaskResult getResult(){return null;} public boolean isAsync(){return false;} public boolean isExternalCall(){return false;} public String getToolCallId(){return "call";}
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
                    com.dwinovo.numen.task.BrainFixture.preempt(body,task); check(pins.active(id,"world","new",System.currentTimeMillis()).size()==1,"preemption must retain pin"); com.dwinovo.numen.task.BrainFixture.finish(task); check(pins.active(id,"world","new",System.currentTimeMillis()).isEmpty(),"brain finish releases real pin");
                  }
                }
                """);
            TaskFocusBrainBridgeIntegrationTest.addBrainStubs(sources);
            sources.remove("com.dwinovo.numen.task.TaskSlot");
            sources.put("com.dwinovo.numen.task.TaskState", "package com.dwinovo.numen.task; public enum TaskState { RUNNING, DONE, SUCCESS, TIMEOUT, CANCELLED; public boolean isTerminal(){return this!=RUNNING;} }");
            sources.put("com.dwinovo.numen.task.Task", "package com.dwinovo.numen.task; public interface Task { enum StopReason { PREEMPTED, REPLACED } boolean canRun(com.dwinovo.numen.entity.NumenPlayer p); TaskState tick(com.dwinovo.numen.entity.NumenPlayer p); void stop(com.dwinovo.numen.entity.NumenPlayer p, StopReason r); default void start(com.dwinovo.numen.entity.NumenPlayer p){} default TaskResult result(TaskState s){return new TaskResult();} String name(); }");
            sources.put("com.dwinovo.numen.task.TaskFactory", "package com.dwinovo.numen.task; public class TaskFactory { public static Task task; public static Task create(com.dwinovo.numen.entity.NumenPlayer p,TaskRecord r){return task;} }");
            sources.put("com.dwinovo.numen.task.BrainFixture", """
                package com.dwinovo.numen.task;
                public class BrainFixture {
                  static CompanionBrain brain; static int stops; static Task.StopReason reason;
                  public static void start(java.util.UUID id,com.dwinovo.numen.entity.NumenPlayer body,TaskRecord rec){
                    brain=new CompanionBrain(id); stops=0; reason=null;
                    TaskFactory.task=new Task(){public boolean canRun(com.dwinovo.numen.entity.NumenPlayer p){return true;} public TaskState tick(com.dwinovo.numen.entity.NumenPlayer p){return TaskState.RUNNING;} public void stop(com.dwinovo.numen.entity.NumenPlayer p,StopReason r){stops++; reason=r;} public String name(){return "fixture";}};
                    brain.current.put(body,rec); brain.reconcileTaskFocus(body,rec);
                  }
                  public static void reconcile(com.dwinovo.numen.entity.NumenPlayer body,TaskRecord rec){brain.reconcileTaskFocus(body,rec); brain.reconcileTaskFocus(body,rec);}
                  public static void preempt(com.dwinovo.numen.entity.NumenPlayer body,TaskRecord rec){brain.current.loseBody(body); if(stops!=1 || reason!=Task.StopReason.PREEMPTED || brain.current.record()!=rec || rec.getState()!=TaskState.RUNNING) throw new AssertionError("preemption must retain running slot");}
                  public static void finish(TaskRecord rec){rec.setState(TaskState.SUCCESS); brain.current.settleIfTerminal(); if(!brain.current.isEmpty()) throw new AssertionError("terminal slot not cleared"); brain.current.settleIfTerminal();}
                }
                """);
            List<String> args = new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",tmp.toString()));
            for (var entry : sources.entrySet()) {
                Path file=tmp.resolve(entry.getKey().replace('.', '/')+".java"); Files.createDirectories(file.getParent()); Files.writeString(file,entry.getValue()); args.add(file.toString());
            }
            Path plugin=root.resolve("plugins/worldstate/src/main/java/com/dwinovo/numen/plugins/worldstate");
            for (String source : List.of("TaskFocusLeaseBridge.java","focus/FocusPin.java","focus/FocusPinStore.java")) args.add(plugin.resolve(source).toString());
            Path api=root.resolve("api/common/src/main/java/com/dwinovo/numen/api");
            for (String source : List.of("TaskFocusLeaseService.java","LearnedSkillBodyResolver.java")) args.add(api.resolve(source).toString());
            args.add(root.resolve("api/common/src/main/java/com/dwinovo/numen/task/CompanionBrain.java").toString());
            args.add(root.resolve("api/common/src/main/java/com/dwinovo/numen/task/TaskSlot.java").toString());
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
}
