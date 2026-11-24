package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.core.RddRuntime;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.api.DetectionMode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 060 ordinary-hunger advisory consumer. The real private survivalPreempt method is extracted
 * from the production source and compiled with narrow host/entity ports; no Detector algorithm
 * is rewritten here. This is RED on the old forced-preempt method and GREEN only after the
 * product successor stops ordinary hunger/low-HP-no-food from requesting a survival replan.
 * Not run by this author.
 */
class RddDetectorAdvisoryConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;
    static Class<?> probe;
    static Path root;
    static final String P = "com/dwinovo/numen/plugins/rdd/";

    @BeforeAll static void setup() throws Exception {
        root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("plugins/rdd/src/main/java"))) root = root.getParent();
        assertNotNull(root, "SETUP: repository root not found");
        assertNotNull(ToolProvider.getSystemJavaCompiler(), "SETUP: full JDK required");
        String detector = Files.readString(root.resolve(
                "plugins/rdd/src/main/java/" + P + "RddDetector.java"));
        String method = method(detector, "    private static void survivalPreempt(");
        Map<String,String> units = new LinkedHashMap<>();
        units.put(P + "RddDetectorProbe.java", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*;
            import com.dwinovo.numen.entity.NumenPlayer;
            import com.dwinovo.numen.rdd.core.*;
            import com.dwinovo.numen.rdd.api.PrimaryGoalStatus;
            import com.dwinovo.numen.task.CompanionTickDispatcher;
            public final class RddDetectorProbe {
              static final org.slf4j.Logger LOG = new org.slf4j.Logger() {
                public void info(String s,Object... a){}
                public void warn(String s,Object... a){}
              };
              static final Map<UUID,Long> SURVIVAL_PREEMPT_AT = new HashMap<>();
              static Map<String,Integer> countInventory(NumenPlayer ap){ return Map.of(); }
            """ + method + """
              public static void install(RddRuntime rt,Object owner){
                RddPlugin.RUNTIME=rt; RddPlugin.BODY_OWNER=owner; CompanionTickDispatcher.OWNER=owner;
                RddPlugin.REQUESTS=0; RddReplanTransition.SAFE_CALLS=0; RddGoalDriver.EXPANSION_CALLS=0;
                SURVIVAL_PREEMPT_AT.clear(); RddReplanTransition.SURVIVAL_PREEMPT_AT.clear(); }
              public static void run(int hunger,double hp){ survivalPreempt(new NumenPlayer(hunger,hp),0L); }
              public static int requests(){ return RddPlugin.REQUESTS; }
              public static Object owner(){ return RddPlugin.BODY_OWNER; }
              public static int safeCalls(){ return RddReplanTransition.SAFE_CALLS; }
              public static int expansionCalls(){ return RddGoalDriver.EXPANSION_CALLS; }
            }
            """);
        units.put(P + "RddPlugin.java", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*;
            import com.dwinovo.numen.entity.NumenPlayer;
            import com.dwinovo.numen.rdd.core.RddRuntime;
            public final class RddPlugin {
              public static RddRuntime RUNTIME;
              public static int REQUESTS;
              public static Object BODY_OWNER;
              public static RddRuntime runtime(UUID id){ return RUNTIME; }
              public static boolean requestReplanForSurvival(UUID id, RddSurvivalGate.Decision d,
                  int hunger,double hp,int free,Object expected,NumenPlayer body){ REQUESTS++; return false; }
              public static Object captureObservationToken(net.minecraft.server.MinecraftServer s){ return null; }
              public static void reportExpansionNeeded(UUID id,String primary,String desc){}
              public static void stopPreemptedOwner(UUID id){}
              public static void stopPreemptedOwner(UUID id,Object expected){}
              public static void stopPreemptedOwner(UUID id,Object expected,Object body){}
            }
            """);
        units.put(P + "SideTaskHost.java", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.UUID;
            public final class SideTaskHost {
              public static boolean blocksMainline(UUID id){ return false; }
              public static boolean isActive(UUID id){ return false; }
            }
            """);
        units.put(P + "RddGoalDriver.java", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.UUID;
            public final class RddGoalDriver { public static int EXPANSION_CALLS; public static void needExpansion(UUID id){ EXPANSION_CALLS++; } }
            """);
        units.put(P + "RddReplanTransition.java", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*;
            public final class RddReplanTransition {
              public static final Map<UUID,Long> SURVIVAL_PREEMPT_AT = new HashMap<>();
              public static int SAFE_CALLS;
              public static void observeSafe(UUID id){ SAFE_CALLS++; }
              public static boolean hasSurvivalIntent(UUID id){ return false; }
            }
            """);
        units.put(P + "RddMonitor.java", """
            package com.dwinovo.numen.plugins.rdd;
            import java.util.Map;
            public final class RddMonitor {
              public static void publish(String event,Map<String,?> data){}
              public static void publish(String event,Map<String,?> data,Object token){}
            }
            """);
        units.put("com/dwinovo/numen/task/CompanionTickDispatcher.java", """
            package com.dwinovo.numen.task;
            import java.util.UUID;
            public final class CompanionTickDispatcher { public static Object OWNER; public static Object currentTaskFor(UUID id){ return OWNER; } }
            """);
        units.put("com/dwinovo/numen/entity/NumenPlayer.java", """
            package com.dwinovo.numen.entity;
            import java.util.UUID;
            public class NumenPlayer {
              private final UUID id=UUID.fromString("00000000-0000-0000-0000-000000000043");
              private final FoodData food; private final Inventory inv=new Inventory(); private final double hp;
              public NumenPlayer(int hunger,double hp){ this.food=new FoodData(hunger); this.hp=hp; }
              public UUID getUUID(){ return id; } public FoodData getFoodData(){ return food; }
              public double getHealth(){ return hp; } public Inventory getInventory(){ return inv; }
              public net.minecraft.server.MinecraftServer getServer(){ return null; }
              public static class FoodData { int level; FoodData(int l){level=l;} public int getFoodLevel(){ return level; } }
              public static class Inventory { public int getContainerSize(){ return 36; } public ItemStack getItem(int i){ return new ItemStack(); } }
              public static class ItemStack { public boolean isEmpty(){ return true; } }
            }
            """);
        units.put("net/minecraft/server/MinecraftServer.java", """
            package net.minecraft.server; public class MinecraftServer {}
            """);
        units.put("org/slf4j/Logger.java", """
            package org.slf4j; public interface Logger { void info(String s,Object... a); void warn(String s,Object... a); }
            """);
        var args = new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-classpath",classpath(),"-d",temp.toString()));
        for(var e:units.entrySet()){ Path f=temp.resolve(e.getKey()); Files.createDirectories(f.getParent()); Files.writeString(f,e.getValue()); args.add(f.toString()); }
        var diag=new ByteArrayOutputStream();
        int status=ToolProvider.getSystemJavaCompiler().run(null,diag,diag,args.toArray(String[]::new));
        assertEquals(0,status,"SETUP: isolated Detector consumer compilation failed (not a business result):"+diag);
        loader=new URLClassLoader(new URL[]{temp.toUri().toURL()},RddDetectorAdvisoryConsumerTest.class.getClassLoader()){
            @Override protected synchronized Class<?> loadClass(String n,boolean resolve) throws ClassNotFoundException {
                if(Files.exists(temp.resolve(n.replace('.','/')+".class"))){ Class<?> c=findLoadedClass(n); if(c==null)c=findClass(n); if(resolve)resolveClass(c); return c; }
                return super.loadClass(n,resolve);
            }
        };
        probe=loader.loadClass("com.dwinovo.numen.plugins.rdd.RddDetectorProbe");
    }
    @AfterAll static void close() throws Exception { if(loader!=null) loader.close(); }

    @Test void ordinaryHungerWithOwnerInFlightDoesNotRequestSurvivalReplan() throws Exception {
        Object owner=new Object(); TaskChain chain=activeChain();
        invoke("install", new Class[]{RddRuntime.class,Object.class}, new RddRuntime(chain,new AssetRegistry()), owner);
        String before=chain.toJson(); invoke("run",new Class[]{int.class,double.class},9,20);
        assertEquals(0,(int)call("requests"),"ordinary hunger must not request a survival replan");
        assertSame(owner,call("owner"),"body owner must not change");
        assertEquals(before,chain.toJson(),"mainline state/budget must not change");
    }

    @Test void lowHpWithoutFoodDoesNotRequestSurvivalReplan() throws Exception {
        Object owner=new Object(); TaskChain chain=activeChain();
        invoke("install", new Class[]{RddRuntime.class,Object.class}, new RddRuntime(chain,new AssetRegistry()), owner);
        String before=chain.toJson(); invoke("run",new Class[]{int.class,double.class},9,7);
        assertEquals(0,(int)call("requests"),"low HP without food must not request a survival replan");
        assertSame(owner,call("owner"),"body owner must not change");
        assertEquals(before,chain.toJson(),"mainline state/budget must not change");
    }

    @Test void safeObservationStillCleansThePreviousSurvivalIntent() throws Exception {
        invoke("install", new Class[]{RddRuntime.class,Object.class}, new RddRuntime(activeChain(),new AssetRegistry()), new Object());
        invoke("run",new Class[]{int.class,double.class},10,20);
        assertEquals(1,(int)call("safeCalls"),"safe state must still call observeSafe");
        assertEquals(0,(int)call("requests"),"safe cleanup must not request a replan");
    }

    @Test void pendingUnexpandedPrimaryStillAsksTheGoalDriverToExpand() throws Exception {
        PrimaryGoal primary=PrimaryGoal.unexpanded("P1","phase one",List.of());
        TaskChain chain=new TaskChain(new Goal("G1","objective",List.of(primary)));
        invoke("install", new Class[]{RddRuntime.class,Object.class}, new RddRuntime(chain,new AssetRegistry()), new Object());
        invoke("run",new Class[]{int.class,double.class},9,20);
        assertEquals(1,(int)call("expansionCalls"),"pending-unexpanded must still ask the goal driver");
        assertEquals(0,(int)call("requests"),"expansion must not request a survival replan");
    }

    static TaskChain activeChain(){
        PrimaryGoal primary=PrimaryGoal.unexpanded("P1","phase one",List.of());
        TaskChain chain=new TaskChain(new Goal("G1","objective",List.of(primary)));
        Subtask sub=new Subtask("S1","mine iron",DetectionMode.HARD_CODED,
                Map.of("asset_key","minecraft:iron_ingot","minimum",7),0,0,false);
        chain.expandCurrentPrimary(List.of(sub));
        assertTrue(chain.activateCurrent(Map.of()),"SETUP: activation");
        return chain;
    }
    static void invoke(String n,Class<?>[] types,Object... args) throws Exception { probe.getMethod(n,types).invoke(null,args); }
    static Object call(String n) throws Exception { return probe.getMethod(n).invoke(null); }
    static String method(String text,String sig){ int a=text.indexOf(sig); assertTrue(a>=0,"SETUP: method missing: "+sig); int indent=sig.indexOf(sig.stripLeading()); String close="\n"+" ".repeat(indent)+"}"; int b=text.indexOf(close,a); assertTrue(b>a,"SETUP: method end missing: "+sig); return text.substring(a,b+close.length())+"\n"; }
    static String classpath() throws Exception { var set=new LinkedHashSet<String>(List.of(System.getProperty("java.class.path").split(File.pathSeparator))); for(Class<?> c:List.of(TaskChain.class,RddRuntime.class,AssetRegistry.class,PrimaryGoal.class,Goal.class,Subtask.class,DetectionMode.class,RddSurvivalGate.class)) set.add(Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString()); return String.join(File.pathSeparator,set); }
}
