package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.WorldObservationService;
import com.dwinovo.numen.api.observation.WorldObservationService.*;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.net.*;
import java.io.File;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Compile and invoke the FULL production WorldStateTool at host boundaries (2026-11-06).
 * WHY: testing caller predicates alone cannot prove the actual tool invokes them before reading.
 * USAGE: :plugins:worldstate:test; Minecraft identity/host are stubs, production parser/output are real.
 * Not a running game: proves dispatch/denial/JSON only, not server sampling or delivery.
 */
class SelfToolEntryTest {
    @TempDir Path temp;
    private static String location(Class<?> c) throws Exception {
        return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
    @Test void realEntryRejectsCrossSubjectAndDimensionBeforeReadOrRefreshAndPreservesJson() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(!Files.exists(root.resolve("plugins/worldstate"))) root=root.getParent();
        String pkg="com.dwinovo.numen.plugins.worldstate";
        Map<String,String> stubs=new LinkedHashMap<>();
        stubs.put("com.dwinovo.numen.agent.tool.NumenTool", """
            package com.dwinovo.numen.agent.tool;
            public interface NumenTool { String name(); String description(); java.util.Map<String,Object> parameterSchema();
              void onServerCall(String id,com.google.gson.JsonObject args,com.dwinovo.numen.entity.NumenPlayer self,java.util.function.Consumer<String> reply); }
            """);
        stubs.put("com.dwinovo.numen.entity.NumenPlayer", """
            package com.dwinovo.numen.entity;
            public class NumenPlayer {
              public Object getServer(){return this;}
              public NumenPlayer serverLevel(){return this;}
              public NumenPlayer dimension(){return this;}
              public String location(){return "minecraft:overworld";}
              public java.util.UUID getUUID(){return java.util.UUID.fromString("a43d753e-0000-0000-0000-000000000001");}
              public NumenPlayer blockPosition(){return this;}
              public int getX(){return 0;} public int getY(){return 64;} public int getZ(){return 0;}
            }
            """);
        stubs.put(pkg+".WorldStateService", """
            package com.dwinovo.numen.plugins.worldstate;
            public class WorldStateService {
              public static String worldId(Object server){return "world";}
              public String sessionId(Object server){return "session";}
              public com.dwinovo.numen.worldstate.core.changes.InMemoryChangeLog changeLog(){return new com.dwinovo.numen.worldstate.core.changes.InMemoryChangeLog();}
              public com.dwinovo.numen.worldstate.core.query.ReadResult read(Object s,com.dwinovo.numen.worldstate.core.request.ObservationRequest r){throw new AssertionError("legacy read for SELF");}
              public com.dwinovo.numen.worldstate.core.refresh.RefreshResult refresh(Object s,com.dwinovo.numen.worldstate.core.request.ObservationRequest r){throw new AssertionError("legacy refresh for SELF");}
            }
            """);
        stubs.put(pkg+".WorldStateMonitor","package "+pkg+"; public class WorldStateMonitor { public static void publish(String e,java.util.Map<String,Object> v){} }");
        List<String> compiler=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-classpath",
                location(Observation.class)+File.pathSeparator+location(Gson.class)+File.pathSeparator+location(WorldObservationService.class)
                +File.pathSeparator+location(SelfObservationProjection.class)+File.pathSeparator+location(com.dwinovo.numen.agent.tool.Schema.class),"-d",temp.toString()));
        for(var stub:stubs.entrySet()) {
            Path file=temp.resolve(stub.getKey().replace('.','/')+".java"); Files.createDirectories(file.getParent());
            Files.writeString(file,stub.getValue()); compiler.add(file.toString());
        }
        compiler.add(root.resolve("plugins/worldstate/src/main/java/com/dwinovo/numen/plugins/worldstate/tool/WorldStateTool.java").toString());
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,compiler.toArray(String[]::new)));
        try(URLClassLoader loader=new URLClassLoader(new URL[]{temp.toUri().toURL()},getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(stubs.containsKey(name)||name.equals("com.dwinovo.numen.plugins.worldstate.tool.WorldStateTool")) {
                    synchronized(getClassLoadingLock(name)) { Class<?> c=findLoadedClass(name);if(c==null)c=findClass(name);if(resolve)resolveClass(c);return c; }
                }
                return super.loadClass(name,resolve);
            }
        }) {
            int[] calls={0,0};
            WorldObservationService service=new WorldObservationService(){
                public Response read(Request r){calls[0]++;return SelfWiringContractTest.response(SelfObservationProjection.item(SelfWiringContractTest.sample()));}
                public Response refresh(Request r){calls[1]++;return read(r);}
            };
            Class<?> host=loader.loadClass(pkg+".WorldStateService"), player=loader.loadClass("com.dwinovo.numen.entity.NumenPlayer");
            Class<?> tool=loader.loadClass(pkg+".tool.WorldStateTool");
            Object instance=tool.getConstructor(host,WorldObservationService.class).newInstance(host.getConstructor().newInstance(),service);
            Object self=player.getConstructor().newInstance();
            var entry=tool.getMethod("onServerCall",String.class,JsonObject.class,player,Consumer.class);
            for(String action:List.of("read","refresh","batch_read")) {
                for(String invalid:List.of("subject","dimension","missing")) {
                    JsonObject q=SelfWiringContractTest.args(invalid.equals("subject")?"player:"+UUID.randomUUID():SelfWiringContractTest.TARGET);
                    if(invalid.equals("dimension"))q.addProperty("dimension","minecraft:the_end");
                    if(invalid.equals("missing"))q.remove("target");
                    JsonObject args=q;
                    if(action.equals("batch_read")){args=new JsonObject();JsonArray list=new JsonArray();list.add(q);args.add("queries",list);}
                    args.addProperty("action",action);
                    List<String> reply=new ArrayList<>();
                    entry.invoke(instance,"q",args,self,(Consumer<String>)reply::add);
                    assertFalse(JsonParser.parseString(reply.getFirst()).getAsJsonObject().get("success").getAsBoolean());
                }
            }
            assertArrayEquals(new int[]{0,0},calls);
            for(String action:List.of("read","refresh")) {
                JsonObject args=SelfWiringContractTest.args(SelfWiringContractTest.TARGET);args.addProperty("action",action);
                List<String> reply=new ArrayList<>();entry.invoke(instance,"q",args,self,(Consumer<String>)reply::add);
                JsonObject data=JsonParser.parseString(reply.getFirst()).getAsJsonObject().getAsJsonObject("data");
                assertTrue(data.get("metadata").isJsonObject());
                assertTrue(data.get("items").isJsonArray());
            }
            assertArrayEquals(new int[]{2,1},calls);
        }
    }
}
