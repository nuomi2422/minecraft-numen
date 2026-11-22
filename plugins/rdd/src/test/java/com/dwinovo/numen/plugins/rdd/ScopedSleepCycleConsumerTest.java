package com.dwinovo.numen.plugins.rdd;
import com.dwinovo.numen.rdd.side.*;
import com.dwinovo.numen.rdd.side.sleep.*;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.google.gson.Gson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.nio.file.*;
import java.net.URLClassLoader;
import java.io.File;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual Watcher -> extracted Host reader -> real Type/Engine. MC identity/tick/bed are ports.
 * No trusted cycle DTO is fabricated; production observations must produce it. Not live MC. */
class ScopedSleepCycleConsumerTest {
    public interface Driver {
        boolean supported();void start(boolean overnight);void observe(long tick,long dayTime,boolean sleeping);
        void evaluate();boolean active();int completed();int resumed();String status();String data();
        void bind();void stale(String kind);void clearOld();void pollOld();void recreate();void clearCurrent();void observeBareWake();
        boolean hasCycle();void removeCurrent();void removeOld();void invalidPoll(String kind);
    }
    @TempDir static Path tmp;static Class<?> host;static URLClassLoader loader;
    static String method(String s,String prefix){int a=s.indexOf(prefix);if(a<0)return "";int b=s.indexOf("\n    }",s.indexOf('{',a));return s.substring(a,b+6);}
    static void source(List<String> args,String type,String text)throws Exception{Path p=tmp.resolve(type.replace('.','/')+".java");Files.createDirectories(p.getParent());Files.writeString(p,text);args.add(p.toString());}
    @BeforeAll static void compile()throws Exception{
        Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.isDirectory(root.resolve("plugins/rdd/src/main/java")))root=root.getParent();assertNotNull(root);
        Path production=root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
        String hostCode=Files.readString(root.resolve("plugins/rdd/src/test/resources/sleep-cycle/SleepCycleHost.java.in"));
        String hostSource=Files.readString(production.resolve("SideTaskHost.java"));
        String reader=method(hostSource,"    static com.dwinovo.numen.rdd.side.SleepCycleEvidence readSleepCycle(");
        String cleanup=method(hostSource,"    static void clearTerminalSleepCycle(");
        assertFalse(cleanup.isEmpty(),"SETUP actual terminal cleanup method");
        String plugin=Files.readString(production.resolve("RddPlugin.java"));
        int removeAt=plugin.indexOf("        numen.on(CompanionEvent.REMOVE, body -> {");
        int clearAt=plugin.indexOf("            SleepWakeWatcher.clearBody(body);",removeAt);
        assertTrue(removeAt>=0&&clearAt>removeAt,"SETUP actual REMOVE cleanup statement");
        String remove=plugin.substring(clearAt,plugin.indexOf('\n',clearAt));
        if(reader.isEmpty())reader="static Object readSleepCycle(NumenPlayer ap,SideTask task){return null;}";
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-classpath",System.getProperty("java.class.path")+File.pathSeparator
            +Path.of(Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI())+File.pathSeparator
            +Path.of(TaskChain.class.getProtectionDomain().getCodeSource().getLocation().toURI())+File.pathSeparator
            +Path.of(Gson.class.getProtectionDomain().getCodeSource().getLocation().toURI()),"-d",tmp.toString()));
        source(args,"com.dwinovo.numen.plugins.rdd.SleepCycleHost",hostCode.replace("// @HOST_READ@",reader).replace("// @TERMINAL_CLEAR@",cleanup).replace("// @REMOVE_CLEAR@",remove));
        args.add(production.resolve("SleepWakeWatcher.java").toString());
        source(args,"net.minecraft.server.level.ServerPlayer","package net.minecraft.server.level; public class ServerPlayer {}");
        source(args,"net.minecraft.server.MinecraftServer","""
            package net.minecraft.server;import java.util.*;import net.minecraft.server.level.ServerPlayer;
            public class MinecraftServer {public List<ServerPlayer> players=new ArrayList<>();public boolean isSameThread(){return true;}
                public PlayerList getPlayerList(){return new PlayerList();}public class PlayerList{public List<ServerPlayer> getPlayers(){return players;}}}
            """);
        source(args,"net.neoforged.neoforge.common.NeoForge","package net.neoforged.neoforge.common;public class NeoForge {public static final Bus EVENT_BUS=new Bus();public static class Bus{public <T>void addListener(java.util.function.Consumer<T> listener){}}}");
        source(args,"net.neoforged.neoforge.event.tick.ServerTickEvent","package net.neoforged.neoforge.event.tick;import net.minecraft.server.MinecraftServer;public class ServerTickEvent{public record Pre(MinecraftServer server){public MinecraftServer getServer(){return server;}}}");
        source(args,"com.dwinovo.numen.api.WorldContextStamp","""
            package com.dwinovo.numen.api;import java.util.*;import net.minecraft.server.MinecraftServer;
            public class WorldContextStamp{public record Token(Map<String,String> identity,long epoch){}
                public static Token token=new Token(Map.of("world_id","fixture-world","server_session","one"),1);public static boolean fail;
                public static Token capture(MinecraftServer server){if(fail)throw new IllegalStateException("fixture unavailable");return token;}}
            """);
        source(args,"com.dwinovo.numen.entity.NumenPlayer",Files.readString(root.resolve("plugins/rdd/src/test/resources/sleep-cycle/SleepCyclePlayer.java.in")));
        var compiler=ToolProvider.getSystemJavaCompiler();assertNotNull(compiler);
        assertEquals(0,compiler.run(null,null,null,args.toArray(String[]::new)),"SETUP: actual Watcher and Host consumer compile");
        loader=new URLClassLoader(new java.net.URL[]{tmp.toUri().toURL()},ScopedSleepCycleConsumerTest.class.getClassLoader()){
            protected Class<?> loadClass(String name,boolean resolve)throws ClassNotFoundException{
                if((name.startsWith("com.dwinovo.numen.plugins.rdd.")&&!name.startsWith(ScopedSleepCycleConsumerTest.class.getName()))
                    ||name.startsWith("net.minecraft.")||name.startsWith("net.neoforged.")||name.startsWith("com.dwinovo.numen.entity.")||name.startsWith("com.dwinovo.numen.api.WorldContextStamp")){
                    try{Class<?> c=findClass(name);if(resolve)resolveClass(c);return c;}catch(ClassNotFoundException absent){}
                }return super.loadClass(name,resolve);}};
        host=loader.loadClass("com.dwinovo.numen.plugins.rdd.SleepCycleHost");
    }
    Driver fixture()throws Exception{return (Driver)host.getConstructor().newInstance();}
    void supported(Driver f){assertTrue(f.supported(),"explicit overnight/scoped producer capability absent; not a legacy binding defect");}
    @Test void actualNightEntryThenDayWakeCompletesExactlyTheSameOwner()throws Exception{
        Driver f=fixture();supported(f);f.start(true);f.observe(13000,13000,false);f.evaluate();
        f.observe(13001,13001,true);assertEquals("OBSERVED_SLEEP",f.status());f.evaluate();assertTrue(f.active());assertEquals(0,f.completed());
        for(long tick=13002;tick<13020;tick++)f.observe(tick,13000+(tick-13000),true);
        f.observe(13020,24000,false);f.evaluate();assertFalse(f.active());assertEquals(1,f.completed());assertEquals(1,f.resumed());
        assertTrue(f.data().contains("13001"));assertTrue(f.data().contains("24000"));f.evaluate();assertEquals(1,f.completed());
    }
    @Test void bindingAndBareWakeDoNotFinishExplicitOvernightButLegacyBindingStillWorks()throws Exception{
        Driver f=fixture();supported(f);f.start(true);f.observe(13000,13000,false);f.evaluate();f.bind();f.observeBareWake();f.evaluate();assertTrue(f.active());assertEquals(0,f.completed());
        f=fixture();f.start(false);f.evaluate();f.bind();f.evaluate();assertFalse(f.active());assertEquals(1,f.completed());
    }
    @Test void initialTrueAndDaytimeNapNeverProduceNightSuccess()throws Exception{
        Driver f=fixture();supported(f);f.start(true);f.observe(13000,13000,true);f.observe(13001,24000,false);f.evaluate();assertEquals(0,f.completed());
        f=fixture();f.start(true);f.observe(13000,6000,false);f.observe(13001,6001,true);f.observe(13002,6002,false);f.evaluate();assertEquals(0,f.completed());
    }
    @Test void changedScopeAndUnknownIdentityCannotReuseTheOldSleepingTrue()throws Exception{
        for(String kind:List.of("world","session","dimension","body","server","unknown","owner")){
            Driver f=fixture();supported(f);f.start(true);f.observe(13000,13000,false);f.observe(13001,13001,true);f.stale(kind);f.observe(13002,24000,false);f.evaluate();assertEquals(0,f.completed(),kind);
        }
    }
    @Test void stalePendingDuplicateReadAndLateCleanupCannotCompleteOrClearANewOwner()throws Exception{
        Driver f=fixture();supported(f);f.start(true);f.observe(13000,13000,false);f.observe(13001,13001,true);f.observe(13002,24000,false);
        assertEquals("DAYBREAK",f.status());assertEquals("UNKNOWN",f.status(),"terminal evidence consumed at most once");
        f.recreate();f.observe(13003,37000,false);f.observe(13004,37001,true);f.clearOld();f.observe(13005,48000,false);f.evaluate();assertEquals(1,f.completed());
    }
    @Test void staleOwnerPollCannotEraseANewerCycleOnTheSameBody()throws Exception{
        Driver f=fixture();supported(f);f.start(true);f.observe(13000,13000,false);f.recreate();
        f.observe(13001,13001,false);f.observe(13002,13002,true);f.pollOld();
        f.observe(13003,24000,false);f.evaluate();assertEquals(1,f.completed(),"old poll must compare owner before cleanup");
    }
    @Test void terminalAndCapturedBodyRemovalReleaseOnlyTheirOwnCycle()throws Exception{
        Driver f=fixture();supported(f);f.start(true);f.observe(13000,13000,false);f.observe(13001,13001,true);assertTrue(f.hasCycle());
        f.removeCurrent();assertFalse(f.hasCycle());
        f=fixture();f.start(true);f.observe(13000,13000,false);f.observe(13001,13001,true);f.stale("body");
        f.observe(13002,13002,false);f.observe(13003,13003,true);f.removeOld();assertTrue(f.hasCycle());
        f.observe(13004,24000,false);f.evaluate();assertEquals(1,f.completed());assertFalse(f.hasCycle(),"production terminal cleanup releases consumed cycle");
    }
    @Test void transientPollScopeChangesAndReadFailureInvalidateTheCapturedTrue()throws Exception{
        for(String kind:List.of("world","throw")){
            Driver f=fixture();supported(f);f.start(true);f.observe(13000,13000,false);f.observe(13001,13001,true);
            f.invalidPoll(kind);assertFalse(f.hasCycle(),kind);
            f.observe(13002,24000,false);f.evaluate();assertEquals(0,f.completed(),kind);
            f.observe(13003,37000,false);f.observe(13004,37001,true);f.observe(13005,48000,false);f.evaluate();
            assertEquals(1,f.completed(),"fresh observed cycle may continue after unavailable evidence: "+kind);
        }
    }
    @Test void interruptedAndExpiredCyclesNeverEmitSuccessfulOvernight()throws Exception{
        Driver f=fixture();supported(f);f.start(true);f.observe(13000,13000,false);f.observe(13001,13001,true);f.observe(13002,13002,false);f.evaluate();assertEquals(0,f.completed());
        f=fixture();f.start(true);f.observe(40001,24000,false);f.evaluate();assertEquals(0,f.completed());assertFalse(f.active());
    }
}
