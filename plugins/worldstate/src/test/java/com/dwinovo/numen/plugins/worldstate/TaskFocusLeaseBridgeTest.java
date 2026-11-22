package com.dwinovo.numen.plugins.worldstate;

import org.junit.jupiter.api.Test;
import javax.tools.ToolProvider;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;

/** Offline integration: compile the real bridge and store, replacing only their external boundaries.
 * This does not exercise Minecraft scheduling or the production lifecycle callbacks. */
class TaskFocusLeaseBridgeTest {
    @Test void bridgeToRealStore() throws Exception { runFixture(); }
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
                  public NumenPlayer level(){return this;} public NumenPlayer serverLevel(){return this;}
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
                public class NumenPlugins { public static Object resolver; public static <T> T capability(Class<T> c){return c.cast(resolver);} }
                """);
            sources.put("com.dwinovo.numen.task.TaskState", "package com.dwinovo.numen.task; public enum TaskState { RUNNING, DONE }");
            sources.put("com.dwinovo.numen.task.TaskRecord", """
                package com.dwinovo.numen.task;
                import com.dwinovo.numen.api.WorldContextStamp;
                public class TaskRecord {
                  public record Selection(String companionId,String skillRef,String version,String bodyDigest,WorldContextStamp token){}
                  public Selection selection; public WorldContextStamp stamp;
                  public TaskState getState(){return TaskState.RUNNING;}
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
                      String bodyRef="skill:demo@v1", version="v1";
                      public Resolution resolve(UUID c,String r,View v){check(c.equals(id)&&v==View.EXECUTOR,"body lookup identity/view"); return new Resolution(bodyRef,text,"");}
                      public FocusDeclaration resolveFocusDeclaration(UUID c,String r,View v){check(c.equals(id)&&v==View.EXECUTOR,"declaration identity/view"); return new FocusDeclaration(r,"environment",version,"");}
                    }
                    var resolver=new Resolver(); NumenPlugins.resolver=resolver;
                    var storeHost=new WorldStateService(); var bridge=new TaskFocusLeaseBridge(storeHost);
                    var task=new TaskRecord(); task.stamp=newStamp; task.selection=new TaskRecord.Selection(id.toString(),"skill:demo","v1",digest,newStamp);
                    WorldStateMod.active=newServer; WorldContextStamp.current=newStamp;
                    var lease=bridge.acquire(body,task,"owner"); check(lease!=null,"valid declaration must acquire");
                    var pins=storeHost.focusPins(); check(pins.active(id,"world","new",System.currentTimeMillis()).size()==1,"real store must contain pin");
                    task.selection=new TaskRecord.Selection(id.toString(),"skill:demo","v1","bad-digest",newStamp);
                    check(bridge.acquire(body,task,"bad-digest-owner")==null,"wrong digest must fail");
                    task.selection=new TaskRecord.Selection(id.toString(),"skill:demo","v1",digest,newStamp);
                    resolver.version="v2"; check(bridge.acquire(body,task,"bad-version-owner")==null,"wrong contract version must fail"); resolver.version="v1";
                    resolver.bodyRef="skill:demo@v2"; check(bridge.acquire(body,task,"bad-ref-owner")==null,"wrong body version must fail"); resolver.bodyRef="skill:demo@v1";
                    var oldTask=new TaskRecord(); oldTask.stamp=oldStamp; oldTask.selection=new TaskRecord.Selection(id.toString(),"skill:demo","v1",digest,oldStamp);
                    check(bridge.acquire(oldBody,oldTask,"old-owner")==null,"old server must be rejected");
                    check(pins.active(id,"world","new",System.currentTimeMillis()).size()==1,"old server must not clear new lease");
                    bridge.release(lease); check(pins.active(id,"world","new",System.currentTimeMillis()).isEmpty(),"release removes sole real holder");
                  }
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
