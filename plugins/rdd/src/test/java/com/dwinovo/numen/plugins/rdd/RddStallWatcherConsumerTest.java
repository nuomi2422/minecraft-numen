package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.*;
import com.google.gson.Gson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Compiles whole, unmodified current Watcher/Policy sources. Only MC IO, timer/LLM
 * snapshots and notification ports are seams. Real track/observe/handleStalled and
 * TaskChain execute together. Detector admission, real API owner-stop delivery,
 * actual MC reads and historical loaded jar identity are outside this fixture.
 */
class RddStallWatcherConsumerTest {
    private static final String PKG="com.dwinovo.numen.plugins.rdd.";
    @TempDir static Path compiled;
    private static URLClassLoader loader;
    private static Class<?> harness;
    @BeforeAll static void compileWholeConsumer() throws Exception {
        var sources=new ArrayList<String>();
        for(String name:List.of("RddStallWatcher","RddStallPolicy")) {
            Path file=compiled.resolve(name+".java");
            Files.copy(root().resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/"+name+".java"),file);
            sources.add(file.toString());
        }
        for(var e:SEAMS.entrySet()) {
            Path file=compiled.resolve(e.getKey()+".java");
            Files.writeString(file,e.getValue()); sources.add(file.toString());
        }
        var compiler=ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler,"SETUP ERROR: whole-source fixture requires JDK");
        var args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-classpath",
                location(TaskChain.class)+File.pathSeparator+location(Gson.class)+File.pathSeparator
                +System.getProperty("java.class.path"),"-sourcepath",compiled.toString(),"-d",compiled.toString()));
        args.addAll(sources);
        var diagnostics=new ByteArrayOutputStream();
        assertEquals(0,compiler.run(null,diagnostics,diagnostics,args.toArray(String[]::new)),
                ()->"SETUP ERROR: whole consumer compilation:\n"+diagnostics.toString(StandardCharsets.UTF_8));
        loader=new URLClassLoader(new URL[]{compiled.toUri().toURL()},RddStallWatcherConsumerTest.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(name.startsWith(PKG)||name.startsWith("net.minecraft.")||name.startsWith("org.slf4j.")
                        ||name.equals("com.dwinovo.numen.entity.NumenPlayer")
                        ||name.startsWith("com.dwinovo.numen.task.CompanionTickDispatcher")
                        ||name.equals("com.dwinovo.numen.task.TimerRegistry")
                        ||name.startsWith("com.dwinovo.numen.agent.llm.LlmActivity")) {
                    Class<?> type=findLoadedClass(name);
                    if(type==null)type=findClass(name);
                    if(resolve)resolveClass(type);
                    return type;
                }
                return super.loadClass(name,resolve);
            }
        };
        harness=loader.loadClass(PKG+"StallHarness");
        for(String name:List.of("RddStallWatcher","RddStallPolicy","StallHarness"))
            assertEquals(compiled.toUri().toURL(),loader.loadClass(PKG+name).getProtectionDomain().getCodeSource().getLocation());
        assertSame(TaskChain.class,loader.loadClass(TaskChain.class.getName()));
    }
    @AfterAll static void closeLoader() throws Exception { if(loader!=null)loader.close(); }

    @Test void stationaryInventoryReachesIdleAndStalledConsumers() throws Exception {
        var f=new Fixture(true); f.ticks(11,false);
        assertEquals(10,f.count()); assertEquals(1,f.events("llm_idle_stall"));
        f.ticks(5,false);
        assertEquals(SubtaskStatus.STALLED,f.chain.currentSubtaskStatus());
        assertEquals(1,f.events("subtask_stalled"));
    }
    @Test void inventoryWalkingDoesNotResetOrRepeatIdleReminder() throws Exception {
        var f=new Fixture(true); f.ticks(11,true);
        assertEquals(10,f.count()); assertEquals(1,f.events("llm_idle_stall"));
        f.ticks(3,true); assertEquals(13,f.count()); assertEquals(1,f.events("llm_idle_stall"));
    }
    @Test void realItemChangeRestartsInventoryWindow() throws Exception {
        var f=new Fixture(true); f.ticks(9,false); assertEquals(8,f.count());
        f.set("inventory",5); f.tick(); assertEquals(0,f.count());
        f.ticks(9,true); assertEquals(0,f.events("llm_idle_stall"));
        f.tick(); assertEquals(1,f.events("llm_idle_stall"));
    }
    @Test void stalledInventoryResumesOnlyOnItemsAndReusesSameWindow() throws Exception {
        var f=new Fixture(true); f.ticks(16,false);
        assertEquals(SubtaskStatus.STALLED,f.chain.currentSubtaskStatus());
        f.set("x",120); f.call("handle");
        assertEquals(SubtaskStatus.STALLED,f.chain.currentSubtaskStatus());
        f.set("inventory",5); f.call("handle");
        assertEquals(SubtaskStatus.RUNNING,f.chain.currentSubtaskStatus()); assertEquals(0,f.count());
        f.tick(); assertEquals(1,f.count(),"handleStalled and track must share the window format");
    }
    @Test void nonInventoryTravelAndWorkKeepOriginalProgress() throws Exception {
        var f=new Fixture(false); f.ticks(40,true);
        assertEquals(0,f.events("llm_idle_stall")); assertEquals(SubtaskStatus.RUNNING,f.chain.currentSubtaskStatus());
        f.set("body",true);
        for(int i=0;i<40;i++){f.set("progress",i); f.tick();}
        assertEquals(0,f.count()); assertEquals(0,f.events("dead_task_stall"));
    }
    @Test void nonInventoryStalledWorkProgressStillResumes() throws Exception {
        var f=new Fixture(false); f.ticks(16,false);
        assertEquals(SubtaskStatus.STALLED,f.chain.currentSubtaskStatus());
        f.set("body",true); f.set("progress",1); f.call("handle");
        assertEquals(SubtaskStatus.RUNNING,f.chain.currentSubtaskStatus());
        f.tick(); assertEquals(1,f.count());
    }
    @Test void activeBodyKeepsBusySourceAndOriginalGrace() throws Exception {
        var f=new Fixture(true); f.set("body",true); f.ticks(21,false);
        assertEquals(20,f.count()); assertEquals(0,f.events("llm_idle_stall"));
        assertEquals(SubtaskStatus.RUNNING,f.chain.currentSubtaskStatus());
    }
    @Test void inFlightLlmSuppressesIdleReminderAtExistingThreshold() throws Exception {
        var f=new Fixture(true); f.set("llm",true); f.set("streak",3); f.ticks(12,false);
        assertEquals(11,f.count()); assertEquals(0,f.events("llm_idle_stall"));
        f.set("llm",false); f.tick(); assertEquals(1,f.events("llm_idle_stall"));
    }
    @Test void registeredTimerClearsWindowUntilConsumed() throws Exception {
        var f=new Fixture(true); f.ticks(8,false); assertEquals(7,f.count());
        f.set("timer",true); f.ticks(50,true); assertEquals(-1,f.count());
        assertEquals(0,f.events("llm_idle_stall"));
        f.set("timer",false); f.ticks(11,false); assertEquals(1,f.events("llm_idle_stall"));
    }
    @Test void disabledSupervisionDoesNotObserveOrNotify() throws Exception {
        var f=new Fixture(true); f.set("supervision",false); f.ticks(30,true);
        assertEquals(-1,f.count()); assertEquals(0,f.call("observations")); assertEquals(0,f.call("sent"));
    }
    @Test void outboundRefusalIsNotRecordedAsDelivery() throws Exception {
        var f=new Fixture(true);
        // This substitutes the existing outbound port, not the actual API owner-stop latch.
        f.set("allowed",false); f.set("streak",3); f.ticks(12,false); assertEquals(0,f.call("sent"));
        f.set("allowed",true); f.tick(); assertEquals(1,f.events("llm_idle_stall"));
        f.tick(); assertEquals(1,f.events("llm_idle_stall"));
    }
    @Test void nextNodeDoesNotInheritPreviousObservationCount() throws Exception {
        var f=new Fixture(true); f.ticks(9,false); assertEquals(8,f.count());
        f.chain.applyHardCodedResult("s0",true); f.rt.startCurrent(); f.tick(); assertEquals(0,f.count());
        f.ticks(9,false); assertEquals(0,f.events("llm_idle_stall"));
        f.tick(); assertEquals(1,f.events("llm_idle_stall"));
    }

    private static final class Fixture {
        final TaskChain chain; final RddRuntime rt; final Object host;
        Fixture(boolean inventory) throws Exception {
            Map<String,Object> condition=inventory?Map.of("asset_key","minecraft:oak_log","minimum",4)
                    :Map.of("type","block_mined","block","minecraft:stone","minimum",6);
            chain=new TaskChain(new Goal("g","goal",List.of(new PrimaryGoal("p","phase",List.of(
                    Subtask.hardCoded("s0","first",condition),Subtask.hardCoded("s1","second",condition))))));
            rt=new RddRuntime(chain,new AssetRegistry()); rt.startCurrent();
            host=harness.getConstructor(RddRuntime.class).newInstance(rt);
        }
        Object invoke(String name,Class<?>[] types,Object... args) throws Exception {
            try{return harness.getMethod(name,types).invoke(host,args);}
            catch(InvocationTargetException ex){
                if(ex.getCause() instanceof Error e)throw e;
                if(ex.getCause() instanceof Exception e)throw e;
                throw ex;
            }
        }
        Object call(String name) throws Exception{return invoke(name,new Class<?>[0]);}
        void set(String key,Object value) throws Exception{invoke("set",new Class<?>[]{String.class,Object.class},key,value);}
        void tick() throws Exception{call("track");}
        void ticks(int n,boolean moving) throws Exception{for(int i=0;i<n;i++){if(moving)set("x",i+100);tick();}}
        int count() throws Exception{return (int)call("count");}
        int events(String name) throws Exception{return (int)invoke("events",new Class<?>[]{String.class},name);}
    }
    private static String location(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }
    private static Path root() {
        for(Path p=Path.of("").toAbsolutePath();p!=null;p=p.getParent())
            if(Files.isRegularFile(p.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddStallWatcher.java")))return p;
        throw new AssertionError("SETUP ERROR: current Watcher source not found");
    }

    private static final Map<String,String> SEAMS=Map.ofEntries(
        Map.entry("StallHarness","""
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*;
            import com.dwinovo.numen.entity.NumenPlayer;
            import com.dwinovo.numen.rdd.core.RddRuntime;
            import com.dwinovo.numen.rdd.api.Subtask;
            import com.dwinovo.numen.task.*;
            import com.dwinovo.numen.agent.llm.LlmActivity;
            public final class StallHarness {
                static final List<String> emitted=new ArrayList<>();
                static int sent,observations;
                final NumenPlayer ap=new NumenPlayer();
                final RddRuntime rt;
                final RddStallWatcher owner=new RddStallWatcher(new RddFurnaceWatch());
                public StallHarness(RddRuntime rt) {
                    this.rt=rt; emitted.clear(); sent=0; observations=0;
                    RddPlugin.enabled=true; RddPlugin.allowed=true;
                    TimerRegistry.pending=false; CompanionTickDispatcher.active=false;
                    CompanionTickDispatcher.progress=0; LlmActivity.flying=false; LlmActivity.streak=0;
                }
                public void track(){owner.track(ap,rt,rt.chain().currentSubtask());}
                public void handle(){owner.handleStalled(ap,rt,rt.chain().currentSubtask());}
                public int count() throws Exception {
                    var f=RddStallWatcher.class.getDeclaredField("stalls"); f.setAccessible(true);
                    Object st=((Map<?,?>)f.get(owner)).get(ap.getUUID());
                    if(st==null)return -1;
                    var m=st.getClass().getDeclaredMethod("unchangedTicks"); m.setAccessible(true);
                    return (int)m.invoke(st);
                }
                public int events(String name){return (int)emitted.stream().filter(name::equals).count();}
                public int observations(){return observations;}
                public int sent(){return sent;}
                public void set(String key,Object value){
                    switch(key){
                        case "inventory" -> ap.getInventory().items.get(0).count=(Integer)value;
                        case "x" -> ap.x=(Integer)value;
                        case "body" -> CompanionTickDispatcher.active=(Boolean)value;
                        case "progress" -> CompanionTickDispatcher.progress=(Integer)value;
                        case "llm" -> LlmActivity.flying=(Boolean)value;
                        case "streak" -> LlmActivity.streak=(Integer)value;
                        case "timer" -> TimerRegistry.pending=(Boolean)value;
                        case "supervision" -> RddPlugin.enabled=(Boolean)value;
                        case "allowed" -> RddPlugin.allowed=(Boolean)value;
                        default -> throw new IllegalArgumentException(key);
                    }
                }
            }
            final class RddPlugin {
                static boolean enabled,allowed;
                static boolean supervisionEnabled(){return enabled;}
                static boolean nudgeAllowed(UUID id){return enabled&&allowed;}
                static boolean nudge(UUID id,String text){if(!nudgeAllowed(id))return false;StallHarness.sent++;return true;}
            }
            final class RddMonitor {
                static void publish(String name,Map<String,?> data){StallHarness.emitted.add(name);}
            }
            final class RddDetector {
                static Map<String,Integer> countInventory(NumenPlayer ap){
                    var out=new LinkedHashMap<String,Integer>();
                    for(var s:ap.getInventory().items)if(!s.isEmpty())out.merge(s.id,s.count,Integer::sum);
                    return out;
                }
            }
            final class RddFurnaceWatch {
                record FurnaceWatch(net.minecraft.world.inventory.AbstractContainerMenu menu){}
                FurnaceWatch track(NumenPlayer ap,RddRuntime rt,Subtask task){StallHarness.observations++;return null;}
                static void appendContainer(StringBuilder b,net.minecraft.world.inventory.AbstractContainerMenu m,NumenPlayer ap){}
            }
            """),
        Map.entry("NumenPlayer","""
            package com.dwinovo.numen.entity;
            public final class NumenPlayer extends net.minecraft.world.entity.player.Player {
                private final java.util.UUID id=java.util.UUID.randomUUID();
                public int x=100;
                public net.minecraft.world.inventory.AbstractContainerMenu containerMenu,inventoryMenu;
                public java.util.UUID getUUID(){return id;}
                public Object getServer(){return this;}
                public net.minecraft.core.BlockPos blockPosition(){return new net.minecraft.core.BlockPos(x,68,100);}
            }
            """),
        Map.entry("Player","""
            package net.minecraft.world.entity.player;
            public class Player {
                private final Inventory inventory=new Inventory();
                public Inventory getInventory(){return inventory;}
            }
            """),
        Map.entry("Inventory","""
            package net.minecraft.world.entity.player;
            public final class Inventory {
                public final java.util.List<net.minecraft.world.item.ItemStack> items=new java.util.ArrayList<>(
                    java.util.List.of(new net.minecraft.world.item.ItemStack("minecraft:spruce_log",4)));
            }
            """),
        Map.entry("ItemStack","""
            package net.minecraft.world.item;
            public final class ItemStack {
                public final String id;public int count;
                public ItemStack(String id,int count){this.id=id;this.count=count;}
                public boolean isEmpty(){return count<=0;} public int getCount(){return count;}
                public ItemStack getItem(){return this;} public ItemStack builtInRegistryHolder(){return this;}
                public ItemStack key(){return this;} public String location(){return id;}
            }
            """),
        Map.entry("BlockPos","""
            package net.minecraft.core;
            public record BlockPos(int x,int y,int z){
                public int getX(){return x;}public int getY(){return y;}public int getZ(){return z;}
            }
            """),
        Map.entry("AbstractContainerMenu","""
            package net.minecraft.world.inventory;
            public class AbstractContainerMenu {
                public int getBurnProgress(){return 0;} public boolean isLit(){return false;}
                public Slot getSlot(int n){return new Slot();}
                public static final class Slot {
                    public net.minecraft.world.item.ItemStack getItem(){return new net.minecraft.world.item.ItemStack("empty",0);}
                }
            }
            """),
        Map.entry("CompanionTickDispatcher","""
            package com.dwinovo.numen.task;
            public final class CompanionTickDispatcher {
                public static boolean active;public static int progress;
                public static Body currentTaskFor(java.util.UUID id){return active?new Body():null;}
                public static final class Body {
                    public State getState(){return new State();}public String getToolName(){return "mine";}
                    public String describe(){return "progress="+progress;}public String publicId(){return "t1";}
                }
                public static final class State {public boolean isTerminal(){return false;}}
            }
            """),
        Map.entry("TimerRegistry","""
            package com.dwinovo.numen.task;
            public final class TimerRegistry {
                public static boolean pending;
                public static TimerRegistry get(Object server){return new TimerRegistry();}
                public java.util.List<String> list(java.util.UUID id){return pending?java.util.List.of("timer1"):java.util.List.of();}
            }
            """),
        Map.entry("LlmActivity","""
            package com.dwinovo.numen.agent.llm;
            public final class LlmActivity {
                public static boolean flying;public static int streak;
                public static Snapshot snapshot(String id){return new Snapshot();}
                public static void markStaleReported(String id,long token){}
                public static final class Snapshot {
                    public boolean inFlight(){return flying;}public boolean stale(){return false;}
                    public boolean staleUnreported(){return false;}public String phase(){return "execution";}
                    public int inFlightCount(){return flying?1:0;}public long oldestInFlightNanos(){return 0;}
                    public long oldestInFlightToken(){return 0;}public int consecutiveNoToolCallResponses(){return streak;}
                    public boolean known(){return true;}public String lastFinish(){return "stop";}
                    public int lastToolCalls(){return 0;}
                }
            }
            """),
        Map.entry("Logger","""
            package org.slf4j;
            public interface Logger {default void warn(String message,Object... args){}}
            """),
        Map.entry("LoggerFactory","""
            package org.slf4j;
            public final class LoggerFactory {public static Logger getLogger(Class<?> type){return new Logger(){};}}
            """)
    );
}
