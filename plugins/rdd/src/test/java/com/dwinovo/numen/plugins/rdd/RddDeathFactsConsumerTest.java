package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.*;
import com.google.gson.Gson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real source-loaded death counts/ledger-record block/history consumers and stores.
 * Body/scanner are explicit MC boundary data, not a new inventory provider. Does NOT execute
 * the complete onCompanionDeath callback, asset invalidation, repair, bed/respawn or risk policy.
 * Preserve the unequal exception semantics of counts and history; do not silently unify them.
 * No tests run until the coordinator releases the Java slot. */
class RddDeathFactsConsumerTest {
    @TempDir static Path compiled;
    @TempDir Path dir;
    static URLClassLoader loader;
    static Class<?> host;
    UUID id;
    static final String SWORD="minecraft:iron_sword", BREAD="minecraft:bread";

    @BeforeAll static void compileConsumers() throws Exception {
        Path plugin=root().resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
        String source=Files.readString(plugin.resolve("RddPlugin.java")).replace("\r\n","\n");
        StringBuilder body=new StringBuilder(HEAD);
        for(String signature:List.of("    private static Map<String, Integer> deathInstantCounts(",
                "    private static void recordDeathLostHistory(", "    static void recordHistoryLost(",
                "    public static AssetHistory history(", "    static void saveHistory(",
                "    static void ensureLedgerLoaded(", "    static void saveLedger(",
                "    public static Map<String, Integer> lastInventory(", "    public static Long lastInventoryAtMillis("))
            body.append(method(source,signature));
        // Verbatim record/cause/catch/save block, but NOT the surrounding death callback.
        body.append("public static RddDeathLedger.Death ledger(UUID id, int lost) { ensureLedgerLoaded(id); NumenPlayer body=BODY; UUID companionId=id;\n")
                .append(between(source,"            Map<String, Integer> lostItems = deathInstantCounts(body);",
                        "            Map<String, Object> invalidatedData = new LinkedHashMap<>();"))
                .append("return death; }\n");
        body.append(between(source,"private static String safeDeathCauseId(","/** 死亡即饿死代理判据（埋点）：死亡瞬间食物条为 0 → starvation。best-effort，失败回 false */"));
        body.append("}\n");
        Path java=compiled.resolve("RddPlugin.java");Files.writeString(java,body.toString());
        Path pos=compiled.resolve("BlockPos.java");
        Files.writeString(pos,"package net.minecraft.core; public record BlockPos(int x,int y,int z) { public int getX(){return x;} public int getY(){return y;} public int getZ(){return z;} public String toShortString(){return x+\", \"+y+\", \"+z;} }");
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-classpath",
                location(TaskChain.class)+File.pathSeparator+location(Gson.class)+File.pathSeparator+System.getProperty("java.class.path"),
                "-d",compiled.toString(),java.toString(),pos.toString()));
        for(String name:List.of("RddDeathLedgerStore","RddHistoryStore")) args.add(plugin.resolve(name+".java").toString());
        var compiler=ToolProvider.getSystemJavaCompiler();assertNotNull(compiler,"SETUP ERROR: JDK required");
        assertEquals(0,compiler.run(null,null,null,args.toArray(String[]::new)),"SETUP ERROR: original consumers must compile");
        loader=new URLClassLoader(new URL[]{compiled.toUri().toURL()},RddDeathFactsConsumerTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name,boolean resolve) throws ClassNotFoundException {
                if(name.startsWith("com.dwinovo.numen.plugins.rdd.") || name.equals("net.minecraft.core.BlockPos")) {
                    Class<?> c=findLoadedClass(name);if(c==null){try{c=findClass(name);}catch(ClassNotFoundException ignored){}}
                    if(c!=null){if(resolve)resolveClass(c);return c;}
                }return super.loadClass(name,resolve);
            }
        };
        host=loader.loadClass("com.dwinovo.numen.plugins.rdd.RddPlugin");
        assertEquals(compiled.toUri().toURL(),host.getProtectionDomain().getCodeSource().getLocation());
    }
    @AfterAll static void close() throws Exception {if(loader!=null)loader.close();}
    @BeforeEach void configure() throws Exception {id=UUID.randomUUID(); call("configure",new Class<?>[]{Path.class,UUID.class},dir,id);}
    @AfterEach void clear(){RddDeathLedger.clear(id);}
    void sample(Map<String,Integer> live,Map<String,Integer> cached,boolean fail) throws Exception {
        call("sample",new Class<?>[]{Map.class,Map.class,boolean.class},live,cached,fail);
    }
    @SuppressWarnings("unchecked") Map<String,Integer> counts() throws Exception {return (Map<String,Integer>)call("counts",new Class<?>[0]);}
    RddDeathLedger.Death record() throws Exception {return (RddDeathLedger.Death)call("ledger",new Class<?>[]{UUID.class,int.class},id,2);}

    @Test void liveCountsWinAndAreFrozenBeforeLedgerPersistence() throws Exception {
        Map<String,Integer> live=new HashMap<>(Map.of(SWORD,1));sample(live,Map.of(BREAD,9),false);
        Map<String,Integer> captured=counts();live.put(SWORD,4);
        assertEquals(Map.of(SWORD,1),captured);assertThrows(UnsupportedOperationException.class,()->captured.put(BREAD,1));
        var death=record();assertEquals(Map.of(SWORD,4),death.lostItems());
        assertEquals("fall|attacker=zombie",death.cause());
        assertEquals(death, RddDeathLedgerStore.load(dir.resolve("ledger"),id).deaths().get(0));
    }
    @Test void emptyOrThrowingLiveUsesExistingCacheWithoutConsumingIt() throws Exception {
        for(boolean fail:List.of(false,true)) {
            sample(Map.of(),Map.of(BREAD,3),fail);assertEquals(Map.of(BREAD,3),counts());
            assertEquals(Map.of(BREAD,3),record().lostItems());assertEquals(Map.of(BREAD,3),counts());
        }
        sample(Map.of(),Map.of(),false);assertTrue(record().lostItems().isEmpty());
        assertEquals(RddDeathLedger.State.UNVERIFIED,RddDeathLedger.latest(id).state(),"empty is not confirmed lost/recovered");
    }
    @Test void restoreOldLedgerBeforeNewRecordAndDoNotReloadOverLive() throws Exception {
        var old=RddDeathLedger.record(id,1,1,"1, 2, 3",1,Map.of(BREAD,2),"old");
        RddDeathLedgerStore.save(dir.resolve("ledger"),id,RddDeathLedger.snapshot(id));RddDeathLedger.clear(id);
        sample(Map.of(SWORD,1),Map.of(),false);var next=record();assertEquals(old.seq()+1,next.seq());
        assertEquals(2,RddDeathLedger.all(id).size());
        RddDeathLedgerStore.save(dir.resolve("ledger"),id,new RddDeathLedger.LedgerSnapshot(1,List.of()));
        assertEquals(next.seq()+1,record().seq(),"ensureLoaded must not overwrite current live ledger twice");
    }
    @Test void historyConsumesLiveOrEmptyFallbackAndPreservesMeaningfulLocation() throws Exception {
        sample(Map.of(SWORD,1,"minecraft:dirt",64),Map.of(BREAD,5),false);
        call("lostHistory",new Class<?>[0]);
        AssetHistory saved=RddHistoryStore.load(dir.resolve("history"),id);
        assertEquals(AssetHistory.State.LOST,saved.get(SWORD).state());assertEquals(1,saved.get(SWORD).lastCount());
        assertEquals("minecraft:overworld",saved.get(SWORD).dimension());assertEquals(10,saved.get(SWORD).x());
        assertNull(saved.get("minecraft:dirt"));assertNull(saved.get(BREAD));
        sample(Map.of(),Map.of(BREAD,5),false);call("lostHistory",new Class<?>[0]);
        assertEquals(5,RddHistoryStore.load(dir.resolve("history"),id).get(BREAD).lastCount());
    }
    @Test void liveReadExceptionFallsBackForLedgerButStillPropagatesForHistory() throws Exception {
        sample(Map.of(),Map.of(BREAD,5),true);assertEquals(Map.of(BREAD,5),record().lostItems());
        assertThrows(IllegalStateException.class,()->call("lostHistory",new Class<?>[0]));
        assertFalse(Files.exists(dir.resolve("history").resolve(id+".json")),"do not silently turn this into counts fallback");
    }
    @Test void ledgerWriteFailureKeepsOriginalInMemoryDeathAndWarns() throws Exception {
        Files.writeString(dir.resolve("ledger"),"blocked");sample(Map.of(SWORD,1),Map.of(),false);
        assertNotNull(record());assertEquals(1,RddDeathLedger.all(id).size());
        assertEquals(true,call("warned",new Class<?>[0]));
        assertFalse(Files.exists(dir.resolve("ledger").resolve(id+".json")));
    }

    static Object call(String name,Class<?>[] types,Object... args)throws Exception{
        try{return host.getMethod(name,types).invoke(null,args);}catch(InvocationTargetException e){
            if(e.getCause() instanceof Exception cause)throw cause;if(e.getCause() instanceof Error cause)throw cause;throw e;}
    }
    static Path root(){for(Path p=Path.of("").toAbsolutePath();p!=null;p=p.getParent())
        if(Files.isRegularFile(p.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd/RddPlugin.java")))return p;
        throw new AssertionError("SETUP ERROR: source root missing");}
    static String location(Class<?> c)throws Exception{return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();}
    static String method(String s,String signature){int a=s.indexOf(signature);assertTrue(a>=0,"SETUP ERROR: "+signature);
        int b=s.indexOf("\n    }",s.indexOf('{',a));assertTrue(b>a);return s.substring(a,b+6)+"\n";}
    static String between(String s,String start,String end){int a=s.indexOf(start),b=s.indexOf(end,a);
        assertTrue(a>=0&&b>a,"SETUP ERROR: consumer slice missing");return s.substring(a,b);}

    static final String HEAD="""
        package com.dwinovo.numen.plugins.rdd;
        import java.util.*;import java.nio.file.*;import java.io.IOException;
        import com.dwinovo.numen.rdd.core.*;import net.minecraft.core.BlockPos;
        public class RddPlugin {
            static Path ledgerDir,historyDir;static final Set<UUID> LEDGER_LOADED=new HashSet<>();
            static final Map<UUID,AssetHistory> HISTORY=new HashMap<>();
            static final Map<UUID,Map<String,Integer>> LAST_INVENTORY=new HashMap<>();
            static final Map<UUID,Long> LAST_INVENTORY_AT=new HashMap<>();
            static NumenPlayer BODY;static boolean warning;static final Log LOG=new Log();
            static class Log{void warn(String s,Object... a){} void info(String s,Object... a){}}
            public static void configure(Path dir,UUID id){ledgerDir=dir.resolve("ledger");historyDir=dir.resolve("history");
                LEDGER_LOADED.clear();HISTORY.clear();LAST_INVENTORY.clear();LAST_INVENTORY_AT.clear();warning=false;BODY=new NumenPlayer(id);}
            public static void sample(Map<String,Integer> live,Map<String,Integer> cached,boolean fail){
                BODY.live=live;BODY.fail=fail;LAST_INVENTORY.put(BODY.id,cached);LAST_INVENTORY_AT.put(BODY.id,123L);}
            public static Map<String,Integer> counts(){return deathInstantCounts(BODY);}
            public static void lostHistory(){recordDeathLostHistory(BODY.id,BODY);}
            public static boolean warned(){return warning;}
            static void warnLedgerStore(String event,Map<String,?> data){warning=true;}
            static class NumenPlayer {
                UUID id;Map<String,Integer> live=Map.of();boolean fail;
                NumenPlayer(UUID id){this.id=id;}UUID getUUID(){return id;}
                String deathCauseId(){return "fall";}String deathCauseAttacker(){return "zombie";}String deathMessage(){return "fell";}
                BlockPos blockPosition(){return new BlockPos(10,64,20);}Level serverLevel(){return new Level();}
                Food getFoodData(){return new Food();}
            }
            static class Food{int getFoodLevel(){return 10;}}
            static class Level{Dimension dimension(){return new Dimension();}}
            static class Dimension{String location(){return "minecraft:overworld";}}
            static class RddDetector{static Map<String,Integer> countInventory(NumenPlayer body){
                if(body.fail)throw new IllegalStateException("scanner unavailable");return body.live;}}
            static class RddInstrumentation{static long currentGameTimeTicks(){return 100L;}}
        """;
}
