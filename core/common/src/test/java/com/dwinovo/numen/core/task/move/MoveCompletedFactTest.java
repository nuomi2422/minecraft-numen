package com.dwinovo.numen.core.task.move;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Compiles the actual result/snapshot methods against controlled world boundaries, not Minecraft physics. */
class MoveCompletedFactTest {
    @TempDir Path temp;
    @Test void productionSnapshotAndResultGates() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("core/common/src/main/java"))) root = root.getParent();
        assertNotNull(root);
        String source = Files.readString(root.resolve("core/common/src/main/java/com/dwinovo/numen/core/task/move/MoveToCompanionTask.java"));
        String stamp = Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/api/WorldContextStamp.java"));
        Path src = temp.resolve("src"), out = temp.resolve("out"); Files.createDirectories(out);
        List<String> args = new ArrayList<>(List.of("-d", out.toString(), "-proc:none"));
        add(src,args,"net/minecraft/server/MinecraftServer.java", "package net.minecraft.server; public class MinecraftServer {} ");
        add(src,args,"com/dwinovo/numen/api/ServerIdentityProvider.java", """
            package com.dwinovo.numen.api; import net.minecraft.server.MinecraftServer;
            public interface ServerIdentityProvider { record Snapshot(String worldId,String serverSession) {} Snapshot snapshot(MinecraftServer s); }
            """);
        add(src,args,"com/dwinovo/numen/api/NumenPlugins.java", """
            package com.dwinovo.numen.api; public class NumenPlugins {
              public static ServerIdentityProvider provider;
              public static <T> T capability(Class<T> c) { return c.cast(provider); }
            }
            """);
        add(src,args,"com/dwinovo/numen/api/WorldContextStamp.java",stamp);
        add(src,args,"fixture/Probe.java", """
            package fixture;
            import java.util.*; import net.minecraft.server.MinecraftServer;
            import com.dwinovo.numen.api.*;
            class Base { protected Map<String,Object> resultData() {return null;} protected Map<String,Object> resultData(TaskState s) {return null;} }
            enum TaskState { SUCCESS, FAILED, CANCELLED, TIMEOUT, RUNNING }
            public class Probe extends Base {
              MinecraftServer moveStartServer; WorldContextStamp.Token moveStartToken;
              String moveStartDimension; double moveStartX,moveStartY,moveStartZ;
              Player player=new Player(); Record r=new Record();
              enum Kind { BLOCK, COLUMN, YLEVEL, FIND } static class Record { Kind kind=Kind.BLOCK; }
              static class Pos { int getY(){return 64;} }
              static class Dimension { String value="overworld"; Dimension location(){return this;} public String toString(){return value;} }
              static class Level { Dimension dim=new Dimension(); Dimension dimension(){return dim;} }
              static class Player { MinecraftServer server=new MinecraftServer(); Level level=new Level(); double x,y=64,z;
                MinecraftServer getServer(){return server;} Level level(){return level;} Pos blockPosition(){return new Pos();}
                double getX(){return x;} double getY(){return y;} double getZ(){return z;}}
            """ + member(source,"    protected Map<String, Object> resultData()") + "\n"
                + member(source,"    private void captureMoveStart()") + "\n"
                + member(source,"    protected Map<String, Object> resultData(TaskState state)") + "\n" + """
              public static Map<String,Object> run(String scenario) {
                Probe p=new Probe(); String[] id={"world", "session"}; boolean[] broken={false};
                NumenPlugins.provider=s->{if(broken[0]) throw new IllegalStateException(); return new ServerIdentityProvider.Snapshot(id[0],id[1]);};
                WorldContextStamp.set("world","session");
                if(scenario.equals("unknown")) id[0]="unknown-test";
                if(scenario.equals("blank")) id[1]=" ";
                if(scenario.equals("start_exception")) broken[0]=true;
                p.captureMoveStart(); broken[0]=false; p.player.x=2;
                if(scenario.equals("same")) p.player.x=0;
                if(scenario.equals("server")) p.player.server=new MinecraftServer();
                if(scenario.equals("dimension")) p.player.level.dim.value="nether";
                if(scenario.equals("world")) id[0]="other";
                if(scenario.equals("session")) id[1]="other";
                if(scenario.equals("epoch")) WorldContextStamp.set("world","session");
                if(scenario.equals("end_exception")) broken[0]=true;
                TaskState state=switch(scenario) {case "failed" -> TaskState.FAILED; case "cancelled" -> TaskState.CANCELLED; case "timeout" -> TaskState.TIMEOUT; case "running" -> TaskState.RUNNING; default -> TaskState.SUCCESS;};
                return p.resultData(state);
              }
            }
            """);
        var compiler=ToolProvider.getSystemJavaCompiler(); assertNotNull(compiler);
        assertEquals(0, compiler.run(null,null,null,args.toArray(String[]::new)), "fixture setup must compile");
        try (var loader=new URLClassLoader(new java.net.URL[]{out.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            var run=loader.loadClass("fixture.Probe").getMethod("run",String.class);
            var success=(Map<?,?>)run.invoke(null,"ok");
            assertEquals(2.0,success.get("final_x"));
            var fact=(Map<?,?>) success.get("completed_move"); assertNotNull(fact);
            assertEquals(false,fact.get("route_proven")); assertEquals("start_to_end_only",fact.get("direction"));
            assertEquals(Map.of("x",2.0,"y",64.0,"z",0.0),fact.get("end"));
            assertTrue(((String)fact.get("target_tolerance")).contains("radius 3"));
            for(String scenario:List.of("same","server","dimension","world","session","epoch","unknown","blank","start_exception","end_exception","failed","cancelled","timeout","running")) {
                var result=(Map<?,?>)run.invoke(null,scenario);
                assertFalse(result.containsKey("completed_move"),scenario);
                assertTrue(result.containsKey("final_x"),scenario);
            }
        }
    }
    static String member(String source,String signature) {
        int a=source.indexOf(signature), b=source.indexOf("\n    }",a);
        if(a<0 || b<0) throw new IllegalStateException("missing member "+signature);
        return source.substring(a,b+6);
    }
    static void add(Path root,List<String> args,String name,String text) throws Exception {
        Path file=root.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file,text); args.add(file.toString());
    }
}
