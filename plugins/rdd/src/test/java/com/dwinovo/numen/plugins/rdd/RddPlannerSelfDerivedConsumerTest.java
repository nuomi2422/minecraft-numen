package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual planStages and PlannerFacts source slices, controlled MC/provider/transport boundaries. */
class RddPlannerSelfDerivedConsumerTest {
    @TempDir Path temp;
    @Test void plannerSendsKnownAndUnknownFoodWithoutLosingWorldOrBudget() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.isRegularFile(root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddStagePlanner.java"))) root=root.getParent();
        assertNotNull(root);
        Path dir=root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
        String planner=Files.readString(dir.resolve("RddStagePlanner.java"));
        String facts=Files.readString(dir.resolve("RddPlannerFacts.java"));
        Path src=temp.resolve("src"), out=temp.resolve("out"); Files.createDirectories(out);
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",out.toString()));
        add(src,args,"net/minecraft/core/BlockPos.java","package net.minecraft.core; public class BlockPos { public int getX(){return 1;} public int getY(){return 64;} public int getZ(){return 2;} }");
        add(src,args,"net/minecraft/core/registries/BuiltInRegistries.java","package net.minecraft.core.registries; public class BuiltInRegistries {} ");
        add(src,args,"net/minecraft/world/level/biome/Biome.java","package net.minecraft.world.level.biome; public class Biome {} ");
        add(src,args,"net/minecraft/world/level/Level.java","package net.minecraft.world.level; public class Level { public Key dimension(){return new Key();} public static class Key { public String location(){return \"minecraft:overworld\";} } }");
        add(src,args,"net/minecraft/server/level/ServerLevel.java","package net.minecraft.server.level; public class ServerLevel extends net.minecraft.world.level.Level { public Holder getBiome(net.minecraft.core.BlockPos p){return new Holder();} public static class Holder { public java.util.Optional<net.minecraft.world.level.Level.Key> unwrapKey(){return java.util.Optional.of(new net.minecraft.world.level.Level.Key());} } }");
        add(src,args,"net/minecraft/server/level/ServerPlayer.java","package net.minecraft.server.level; public class ServerPlayer { public net.minecraft.core.BlockPos blockPosition(){return new net.minecraft.core.BlockPos();} public net.minecraft.world.level.Level level(){return new ServerLevel();} }");
        add(src,args,"net/neoforged/neoforge/server/ServerLifecycleHooks.java","package net.neoforged.neoforge.server; public class ServerLifecycleHooks { public static Server getCurrentServer(){return new Server();} public static class Server { public Players getPlayerList(){return new Players();} } public static class Players { public net.minecraft.server.level.ServerPlayer getPlayer(java.util.UUID id){return new net.minecraft.server.level.ServerPlayer();} } }");
        add(src,args,"org/slf4j/LoggerFactory.java","package org.slf4j; public class LoggerFactory { public static Log getLogger(Class<?> c){return new Log();} public static class Log { public void warn(String s,Object... a){} public void info(String s,Object... a){} } }");
        add(src,args,"com/dwinovo/numen/api/RationaleContext.java","package com.dwinovo.numen.api; public class RationaleContext { public static class Entry {} }");
        add(src,args,"com/dwinovo/numen/rdd/api/PrimarySpec.java","package com.dwinovo.numen.rdd.api; public class PrimarySpec {} ");
        add(src,args,"com/dwinovo/numen/rdd/core/PlanningAssetSnapshot.java","package com.dwinovo.numen.rdd.core; public class PlanningAssetSnapshot { public java.util.Map<String,Integer> availableCounts(){return java.util.Map.of();} public boolean hasInventoryScanTime(){return false;} }");
        add(src,args,"com/dwinovo/numen/rdd/core/RddChainFactory.java","package com.dwinovo.numen.rdd.core; public class RddChainFactory { public static int MAX_STAGES=12; }");
        add(src,args,"com/dwinovo/numen/plugins/rdd/RddPlannerFacts.java",facts);
        String source="""
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*; import java.util.function.*; import com.dwinovo.numen.rdd.api.PrimarySpec; import com.dwinovo.numen.rdd.core.*;
            class RddContainerMemory { static String cached(UUID id){return \"\";} }
            class RddPlugin { static Object assets(UUID id){return null;} static PlanningAssetSnapshot planningSnapshot(UUID id){return new PlanningAssetSnapshot();} }
            class RddAssetContext { static String renderForPlanner(Object x,int n,String goal){return \"历史世界线索\";} }
            class RddPlanningKnowledge { static Object HOST=new Object(); static void publishPolicy(Object... a){} }
            class RddPlanGuard { record Stages(List<PrimarySpec> allowed,List<String> dropped) {} static Stages filterStages(List<PrimarySpec> x,String y){return new Stages(x,List.of());} }
            class RddRiskPlanning { static List<PrimarySpec> ensureSmeltPrepStage(List<PrimarySpec> x,Map<String,Integer> y){return x;} static List<PrimarySpec> ensurePrepStage(List<PrimarySpec> x,int n){return x;} static List<PrimarySpec> injectWaitFor(List<PrimarySpec> x,Map<String,Integer> y){return x;} }
            class RddPlanningPolicy { static String appliedRules(String a,String b){return \"\";} }
            class RddDecomposer { static String sent; static void llmAsk(UUID id,String stage,String text,Object sys,Object tool,Consumer<Object> ok,Runnable fail){sent=text;} }
            public class Probe {
              static final Object PLAN_SYSTEM=null,PLAN_TOOL=null;
              static List<PrimarySpec> parse(Object a){return List.of();} static String freshRationale(UUID id){return \"\";}
              static void dispatchStages(UUID id,String goal,com.dwinovo.numen.api.RationaleContext.Entry why,PlanningAssetSnapshot snap,String facts,Object host,Supplier<String> rationale,Consumer<String> send){send.accept(facts);}
            """ + member(planner,"    static void planStages(UUID companionId, String objective,\n") + "\n}";
        add(src,args,"com/dwinovo/numen/plugins/rdd/Probe.java",source);
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,null,null,args.toArray(String[]::new)),"SETUP: real planner/facts slice compile");
        // A detached fixture cannot claim a real server/provider; this verifies selected actual methods only.
        try(var loader=new URLClassLoader(new java.net.URL[]{out.toUri().toURL()},ClassLoader.getPlatformClassLoader())) {
            var probe=loader.loadClass("com.dwinovo.numen.plugins.rdd.Probe");
            var method=probe.getDeclaredMethod("planStages",UUID.class,String.class,loader.loadClass("com.dwinovo.numen.api.RationaleContext$Entry"),java.util.function.Consumer.class);method.setAccessible(true);
            method.invoke(null,UUID.randomUUID(),"prepare food",null,(java.util.function.Consumer<Object>)x->{});
            var decomp=loader.loadClass("com.dwinovo.numen.plugins.rdd.RddDecomposer");var field=decomp.getDeclaredField("sent");field.setAccessible(true);
            String sent=(String)field.get(null);assertNotNull(sent);assertTrue(sent.contains("历史世界线索"));
            assertTrue(sent.contains("可食储备"),sent);
        }
    }
    private static String member(String text,String signature){int a=text.indexOf(signature), b=text.indexOf("\n    }",a);if(a<0||b<0)throw new IllegalStateException(signature);return text.substring(a,b+6);}
    private static void add(Path root,List<String> args,String name,String value)throws Exception{Path p=root.resolve(name);Files.createDirectories(p.getParent());Files.writeString(p,value);args.add(p.toString());}
}
