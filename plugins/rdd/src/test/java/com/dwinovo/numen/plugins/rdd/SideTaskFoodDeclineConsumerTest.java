package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.side.*;
import com.dwinovo.numen.rdd.side.sleep.*;
import com.dwinovo.numen.rdd.side.death.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Attempt06 (2026-11-17): successful food refusal was recreated on the next tick.
 * Run this exact class through managed plugins:rdd TestOnly. Compile unchanged current
 * Host decline/tick/request/cancel methods and its food trigger registration, plus the
 * whole DeclineTool and type matcher; consume the real Engine/Runtime/Policy/Types.
 * MC body facts, persistence, notification, death planning and client interruption are
 * IO seams, not another cooldown/engine implementation. No Minecraft bootstrap or live
 * delivery is proved. Fixture compilation failure is SETUP ERROR, never a business red.
 * Method extraction follows MineHarvestRecoveryConsumerTest; a new product abstraction
 * or hundreds of unrelated MC mocks would obscure this one missing host clock write.
 */
class SideTaskFoodDeclineConsumerTest {
    @TempDir static Path temp;
    private static final String PKG = "com.dwinovo.numen.plugins.rdd.";
    private static URLClassLoader loader;
    private static Class<?> bridge;
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-000000000012");

    @BeforeAll static void compileOriginalConsumers() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("plugins/rdd/src/main/java"))) root=root.getParent();
        assertNotNull(root, "SETUP ERROR: candidate tree unavailable");
        Path main=root.resolve("plugins/rdd/src/main/java/com/dwinovo/numen/plugins/rdd");
        String host=Files.readString(main.resolve("SideTaskHost.java"));
        var args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-classpath",
                location(SideTaskEngine.class)+File.pathSeparator+location(Gson.class)+File.pathSeparator
                        +System.getProperty("java.class.path"),"-sourcepath",temp.toString(),"-d",temp.toString()));
        StringBuilder consumer=new StringBuilder(HOST_HEADER);
        // Only widen fixture field access for observation; retain the original map allocation.
        consumer.append(statement(host,"    private static final Map<UUID, Long> LAST_FOOD_DONE_MS")
                .replace("private static final", "static final")).append('\n');
        System.out.println("CONSUMER_SOURCE SideTaskHost.java sha256="+sha(host));
        for(String signature:List.of("    static String currentTypeId(UUID companionId)",
                "    static boolean decline(UUID companionId, String reason)",
                "    static void requestFoodSupply(", "    static void tick(MinecraftServer server, NumenPlayer ap)",
                "    static void cancel(UUID companionId, String reason)", "    static void onServerStopped()",
                "    private static boolean foodCritical(")) consumer.append(method(host,signature)).append('\n');
        consumer.append("static final class SleepSafety {\n")
                .append(method(host,"        static boolean mayInterrupt("))
                .append(method(host,"        static void tick(SideTaskEngine engine, UUID id,"))
                .append("}\nstatic {\n")
                .append(statement(host,"        ENGINE.registerTrigger(new com.dwinovo.numen.rdd.side.FoodSupplySideTaskTrigger(ctx ->"))
                .append("}\n").append(HOST_IO).append("}\n");
        add(args,"com/dwinovo/numen/plugins/rdd/SideTaskHost.java",consumer.toString());
        for(String name:List.of("RddDeclineSideTool","RddSideType"))
            {
                String raw=Files.readString(main.resolve(name+".java"));
                System.out.println("CONSUMER_SOURCE "+name+".java sha256="+sha(raw));
                add(args,"com/dwinovo/numen/plugins/rdd/"+name+".java",raw);
            }
        for(var e:IO.entrySet())add(args,e.getKey().replace('.','/')+".java",e.getValue());
        var compiler=ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler,"SETUP ERROR: JDK compiler required");
        var diagnostics=new ByteArrayOutputStream();
        assertEquals(0,compiler.run(null,diagnostics,diagnostics,args.toArray(String[]::new)),
                ()->"SETUP ERROR: original method fixture:\n"+diagnostics.toString(StandardCharsets.UTF_8));
        loader=new URLClassLoader(new URL[]{temp.toUri().toURL()},SideTaskFoodDeclineConsumerTest.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if(name.startsWith(PKG)||name.startsWith("net.minecraft.server.")
                        ||name.startsWith("com.dwinovo.numen.entity.NumenPlayer")
                        ||name.startsWith("com.dwinovo.numen.task.")
                        ||name.startsWith("com.dwinovo.numen.agent.tool.")
                        ||name.equals("com.dwinovo.numen.api.NumenPlugins")) {
                    Class<?> c=findLoadedClass(name);if(c==null)c=findClass(name);if(resolve)resolveClass(c);return c;
                }
                return super.loadClass(name,resolve);
            }
        };
        bridge=loader.loadClass(PKG+"FoodDeclineHarness");
        assertSame(SideTaskEngine.class,loader.loadClass(SideTaskEngine.class.getName()));
        for(String name:List.of("SideTaskHost","RddDeclineSideTool","RddSideType"))
            assertEquals(temp.toUri().toURL(),loader.loadClass(PKG+name).getProtectionDomain().getCodeSource().getLocation());
    }
    @AfterAll static void close() throws Exception {if(loader!=null)loader.close();}
    @BeforeEach void reset() throws Exception {call("reset");DeathRecoverySideTaskTrigger.resetForTest();}

    @Test void successfulFoodDeclineBlocksNextAutomaticTick() throws Exception {
        body(A,12,8,0); tick(A); SideTask first=current(A);assertNotNull(first);
        assertTrue(reply(A,"food_supply","danger").get("success").getAsBoolean());
        assertNull(current(A));assertEquals(1,count("side_task_declined"));
        assertEquals(0,count("side_task_cancelled"));assertEquals(Boolean.TRUE,lastEvent("side_task_resumed_mainline").get("mainlineResumed"));
        tick(A);assertNull(current(A),"successful refusal must survive the next actual host tick");
        assertEquals(1,count("side_task_created"));
        assertEquals("DECLINED",lastEvent("side_task_declined").get("state"));
        assertEquals(first.instanceId(),lastEvent("side_task_declined").get("instanceId"));
        assertTrue(clock(A)>0,"the existing host food clock records only successful food refusal");
        assertEquals(0,((Number)call("cacheSize")).intValue());
    }
    @Test void successfulDeclineAlsoBlocksExplicitSurvivalReportWithoutStoppingBody() throws Exception {
        body(A,12,8,0);tick(A);reply(A,"food_supply","danger");call("clearStops");
        call("request",A);assertNull(current(A),"report must consume the same five-minute host clock");
        assertEquals(0,((Number)call("stops")).intValue());
    }
    @Test void repeatedRefusalOfEmptySlotCannotExtendClock() throws Exception {
        body(A,12,8,0);tick(A);reply(A,"food_supply","danger");
        long marked=clock(A);assertTrue(marked>0);
        assertFalse(reply(A,"food_supply","again").get("success").getAsBoolean());
        assertFalse((Boolean)call("decline",A));assertEquals(marked,clock(A));
        assertEquals(1,count("side_task_declined"));
    }
    @Test void sameGameDayCanSupplyAgainAfterFiveMinutes() throws Exception {
        body(A,12,8,0);tick(A);SideTask first=current(A);reply(A,"food_supply","danger");
        call("clock",A,System.currentTimeMillis()-FoodSupplyPolicy.COOLDOWN_MS+1000);tick(A);
        assertNull(current(A),"still inside the existing five-minute window");
        call("clock",A,System.currentTimeMillis()-FoodSupplyPolicy.COOLDOWN_MS-1000);tick(A);
        assertNotNull(current(A));assertTrue(current(A).generation()>first.generation());
        assertEquals(first.scheduledGameDay(),current(A).scheduledGameDay(),"food is not once per day");
        assertTrue(FoodSupplyPolicy.cooldownActive(1000+FoodSupplyPolicy.COOLDOWN_MS-1,1000));
        assertFalse(FoodSupplyPolicy.cooldownActive(1000+FoodSupplyPolicy.COOLDOWN_MS,1000));
    }
    @Test void newCriticalHungerWithNoFoodBypassesRefusalCooldown() throws Exception {
        body(A,12,8,0);tick(A);reply(A,"food_supply","danger");
        call("clock",A,System.currentTimeMillis());facts(A,6,8,0);tick(A);
        assertEquals("food_supply",current(A).typeId(),"confirmed critical empty-stock hunger keeps its old exemption");
    }
    @Test void explicitReportRetainsUrgentEmptyStockExemption() throws Exception {
        body(A,3,20,0);call("clock",A,System.currentTimeMillis());call("request",A);
        assertEquals("food_supply",current(A).typeId());
    }
    @Test void lowHealthAloneDoesNotBypassExistingCooldown() throws Exception {
        body(A,12,3,0);call("clock",A,System.currentTimeMillis());tick(A);call("request",A);
        assertNull(current(A));assertEquals(0,count("side_task_created"));
    }
    @Test void unknownHungerOrInventoryCannotCreateUrgentBypass() throws Exception {
        for(int[] f:List.of(new int[]{-1,8,0},new int[]{3,8,-1},new int[]{-1,-1,-1})) {
            call("reset");body(A,f[0],f[1],f[2]);call("clock",A,System.currentTimeMillis());tick(A);call("request",A);
            assertNull(current(A),"UNKNOWN must not become urgent empty stock");
        }
    }
    @Test void anotherCompanionDoesNotInheritFoodRefusalClock() throws Exception {
        body(A,12,8,0);body(B,12,8,0);tick(A);reply(A,"food_supply","danger");tick(B);
        assertEquals("food_supply",current(B).typeId());assertEquals(0,clock(B));
    }
    @Test void wrongExpectedTypeLeavesCurrentFoodAndClockUntouched() throws Exception {
        body(A,12,8,0);tick(A);SideTask first=current(A);
        assertFalse(reply(A,"sleep","wrong").get("success").getAsBoolean());
        assertEquals(first,current(A));assertEquals(0,clock(A));assertEquals(0,count("side_task_declined"));
    }
    @Test void emptyReasonAndEmptyActiveRemainFailures() throws Exception {
        body(A,12,8,0);assertFalse(reply(A,"","danger").get("success").getAsBoolean());
        tick(A);assertFalse(reply(A,"food_supply","").get("success").getAsBoolean());
        assertNotNull(current(A));assertEquals(0,clock(A));
        assertFalse((Boolean)call("decline",(Object)null));
    }
    @Test void caseInsensitiveExpectedFoodTypeStillDeclines() throws Exception {
        body(A,12,8,0);tick(A);
        assertTrue(reply(A," FOOD_SUPPLY ","danger").get("success").getAsBoolean());
        assertEquals(1,count("side_task_declined"));
    }
    @Test void externalCancellationDoesNotBecomeFoodRefusalOrInventCooldown() throws Exception {
        body(A,12,8,0);tick(A);call("cancel",A);assertNull(current(A));
        assertEquals(1,count("side_task_cancelled"));assertEquals(0,count("side_task_declined"));
        assertEquals(0,clock(A));tick(A);assertNotNull(current(A));
    }
    @Test void dailySleepDeclineKeepsDayAndWorldScopeWithoutFoodClock() throws Exception {
        body(A,20,20,2);call("time",A,11800L);tick(A);assertEquals("sleep",current(A).typeId());
        assertTrue(reply(A,"sleep","unsafe").get("success").getAsBoolean());
        assertEquals(0,clock(A));tick(A);assertNull(current(A));
        call("world",A,"minecraft:other");tick(A);assertEquals("sleep",current(A).typeId());
        reply(A,"sleep","unsafe");call("time",A,35800L);tick(A);assertEquals("sleep",current(A).typeId());
    }
    @Test void unknownWorldOnlyDeclinesCurrentInstanceWithoutDailyLock() throws Exception {
        body(A,20,20,2);call("time",A,11800L);tick(A);call("available",A,false);
        assertTrue(reply(A,"sleep","world missing").get("success").getAsBoolean());
        call("available",A,true);call("time",A,35800L);tick(A);assertEquals("sleep",current(A).typeId());
        assertEquals(0,clock(A));
    }
    @Test void deathRecoveryDeclineDoesNotSwallowNextDeathSequence() throws Exception {
        body(A,20,20,2);
        DeathRecoverySideTaskTrigger.expect(A,701,"minecraft:overworld","0,64,0",Map.of("minecraft:stone",1));
        tick(A);assertEquals("701",current(A).data().get("death_seq"));reply(A,"death_recovery","unsafe");
        DeathRecoverySideTaskTrigger.expect(A,702,"minecraft:overworld","1,64,0",Map.of("minecraft:stone",1));
        tick(A);assertEquals("702",current(A).data().get("death_seq"));assertEquals(0,clock(A));
    }
    @Test void serverStopClearsActiveTasksAndDailyLocksWithoutResumingOrStartingBody() throws Exception {
        body(A,20,20,2);call("time",A,11800L);tick(A);call("clearStops");call("stop");
        assertNull(current(A));assertEquals(0,count("side_task_resumed_mainline"));
        assertEquals(0,((Number)call("stops")).intValue());
        tick(A);assertEquals("sleep",current(A).typeId(),"original engine stop clears handled-day scope");
    }
    @Test void inTickCompletionAndTimeoutStillWriteExistingFoodClock() throws Exception {
        for(boolean timeout:List.of(false,true)) {
            call("reset");body(A,12,8,0);tick(A);
            if(timeout)call("time",A,20000L);else facts(A,14,20,2);
            tick(A);assertNull(current(A));assertTrue(clock(A)>0);
            assertEquals(1,count(timeout?"side_task_skipped":"side_task_completed"));
        }
    }
    @Test void foodReceiptDoesNotPromiseAnAllDayBan() throws Exception {
        body(A,12,8,0);tick(A);String msg=reply(A,"food_supply","danger").get("message").getAsString();
        assertFalse(msg.contains("again today"),"food repeats after cooldown and retains emergency exemption");
        assertTrue(msg.contains("5-minute"));assertTrue(msg.contains("urgent hunger"));
        String description=(String)call("description");assertFalse(description.contains("offered again today"));
    }

    private static Object call(String name,Object... args) throws Exception {
        Method m=Arrays.stream(bridge.getMethods()).filter(x->x.getName().equals(name)&&x.getParameterCount()==args.length).findFirst().orElseThrow();
        try{return m.invoke(null,args);}catch(InvocationTargetException e){throw new AssertionError("consumer threw",e.getCause());}
    }
    private void body(UUID id,int hunger,int hp,int edible)throws Exception{call("body",id,hunger,hp,edible);}
    private void facts(UUID id,int hunger,int hp,int edible)throws Exception{call("facts",id,hunger,hp,edible);}
    private void tick(UUID id)throws Exception{call("tick",id);}
    private SideTask current(UUID id)throws Exception{return (SideTask)call("current",id);}
    private long clock(UUID id)throws Exception{return ((Number)call("clock",id)).longValue();}
    private int count(String event)throws Exception{return ((Number)call("count",event)).intValue();}
    @SuppressWarnings("unchecked") private Map<String,Object> lastEvent(String event)throws Exception{return (Map<String,Object>)call("lastEvent",event);}
    private JsonObject reply(UUID id,String type,String reason)throws Exception{return JsonParser.parseString((String)call("reply",id,type,reason)).getAsJsonObject();}
    private static String sha(String source)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));}
    private static String location(Class<?> c)throws Exception{return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();}
    private static void add(List<String> args,String path,String source)throws Exception{Path p=temp.resolve(path);Files.createDirectories(p.getParent());Files.writeString(p,source);args.add(p.toString());}
    private static String method(String source,String signature) {
        int start=source.indexOf(signature);assertTrue(start>=0,"SETUP ERROR: missing "+signature);
        int open=source.indexOf('{',start),depth=0;boolean quoted=false,character=false,line=false,block=false,escape=false;
        for(int i=open;i<source.length();i++){
            char c=source.charAt(i),n=i+1<source.length()?source.charAt(i+1):0;
            if(line){if(c=='\n')line=false;continue;}if(block){if(c=='*'&&n=='/'){block=false;i++;}continue;}
            if(quoted||character){if(escape){escape=false;continue;}if(c=='\\'){escape=true;continue;}if(quoted&&c=='"')quoted=false;if(character&&c=='\'')character=false;continue;}
            if(c=='/'&&n=='/'){line=true;i++;continue;}if(c=='/'&&n=='*'){block=true;i++;continue;}
            if(c=='"'){quoted=true;continue;}if(c=='\''){character=true;continue;}
            if(c=='{')depth++;if(c=='}'&&--depth==0)return source.substring(start,i+1);
        }
        throw new AssertionError("SETUP ERROR: unterminated "+signature);
    }
    private static String statement(String source,String signature){int start=source.indexOf(signature);assertTrue(start>=0,"SETUP ERROR: missing registration");int end=source.indexOf(';',start);assertTrue(end>start);return source.substring(start,end+1);}

    private static final String HOST_HEADER="""
        package com.dwinovo.numen.plugins.rdd;
        import java.util.*;
        import java.util.concurrent.ConcurrentHashMap;
        import com.dwinovo.numen.entity.NumenPlayer;
        import net.minecraft.server.MinecraftServer;
        import com.dwinovo.numen.rdd.side.*;
        import com.dwinovo.numen.rdd.side.sleep.*;
        import com.dwinovo.numen.rdd.side.death.*;
        class SideTaskHost {
            static final List<Map<String,Object>> EVENTS=new ArrayList<>();
            static final SideTaskEngine ENGINE=new SideTaskEngine((e,d)->{var row=new HashMap<String,Object>(d);row.put("event",e);EVENTS.add(row);},t->t);
            static final Map<UUID,Long> LAST_NUDGE_GAME_TIME=new HashMap<>();
            static final Set<UUID> FORCED_FOOD=ConcurrentHashMap.newKeySet();
            static final Map<UUID,String> SIDE_CONTEXT=new HashMap<>();
            static final Log LOG=new Log();
            static class Log {void warn(String text,Object... args){throw new AssertionError(text+Arrays.toString(args));}}
            static {ENGINE.registerType(new FoodSupplySideTaskType());ENGINE.registerType(new SleepSideTaskType());ENGINE.registerTrigger(new SleepSideTaskTrigger());ENGINE.registerType(new DeathRecoverySideTaskType());ENGINE.registerTrigger(new DeathRecoverySideTaskTrigger());}
        """;
    private static final String HOST_IO="""
        static void persist() {}
        static void refreshContext(UUID id,SideTaskWorldPort p){if(ENGINE.isActive(id))SIDE_CONTEXT.put(id,"active");else SIDE_CONTEXT.remove(id);}
        static void nudgeIfNeeded(UUID id,SideTaskWorldPort p,boolean was,boolean now){}
        static MainlineResumeToken currentMainlineToken(UUID id){return new MainlineResumeToken(id,"chain","goal","primary","step",1,1);}
        static MainlineResumeToken validateMainline(MainlineResumeToken t){return t;}
        static void tickDeathPreparation(NumenPlayer p,HostWorldPort port){}
        static class HostWorldPort implements SideTaskWorldPort {
            final NumenPlayer ap;com.dwinovo.numen.task.TaskRecord sleepAction;
            HostWorldPort(NumenPlayer p){ap=p;}
            public long gameTime(){return ap.time;}public long dayTime(){return ap.time;}public long gameDay(){return ap.time/24000;}
            public boolean hasDayNightCycle(){return true;}public String worldId(){return ap.world;}
            public boolean isSleeping(UUID id){return false;}public boolean isDay(){return ap.time%24000<12000;}
            public int hunger(UUID id){return ap.hunger;}public int health(UUID id){return ap.hp;}public int edibleCount(UUID id){return ap.edible;}
            public Wake pollWake(UUID id){return Wake.NONE;}
        }
        """;
    private static final Map<String,String> IO=Map.ofEntries(
        Map.entry("com.dwinovo.numen.plugins.rdd.FoodDeclineHarness","""
            package com.dwinovo.numen.plugins.rdd;
            import java.util.*;import com.google.gson.*;import com.dwinovo.numen.entity.NumenPlayer;import com.dwinovo.numen.rdd.side.*;
            public class FoodDeclineHarness {
                public static void reset(){SideTaskHost.ENGINE.onServerStopped();SideTaskHost.EVENTS.clear();SideTaskHost.LAST_FOOD_DONE_MS.clear();SideTaskHost.LAST_NUDGE_GAME_TIME.clear();SideTaskHost.FORCED_FOOD.clear();SideTaskHost.SIDE_CONTEXT.clear();RddCompanionBody.ALL.clear();clearStops();}
                public static void body(UUID id,int hunger,int hp,int edible){var p=new NumenPlayer(id);p.hunger=hunger;p.hp=hp;p.edible=edible;RddCompanionBody.ALL.put(id,p);}
                public static void facts(UUID id,int hunger,int hp,int edible){var p=RddCompanionBody.ALL.get(id);p.hunger=hunger;p.hp=hp;p.edible=edible;}
                public static void time(UUID id,long time){RddCompanionBody.ALL.get(id).time=time;}
                public static void world(UUID id,String world){RddCompanionBody.ALL.get(id).world=world;}
                public static void available(UUID id,boolean ready){RddCompanionBody.ALL.get(id).available=ready;}
                public static SideTask current(UUID id){return SideTaskHost.ENGINE.current(id).orElse(null);}
                public static void tick(UUID id){SideTaskHost.tick(null,RddCompanionBody.ALL.get(id));}
                public static boolean decline(UUID id){return SideTaskHost.decline(id,"direct");}
                public static void request(UUID id){SideTaskHost.requestFoodSupply(RddCompanionBody.ALL.get(id),"survival");}
                public static void cancel(UUID id){SideTaskHost.cancel(id,"external pause");}
                public static void stop(){SideTaskHost.onServerStopped();}
                public static long clock(UUID id){return SideTaskHost.LAST_FOOD_DONE_MS.getOrDefault(id,0L);}
                public static void clock(UUID id,long time){SideTaskHost.LAST_FOOD_DONE_MS.put(id,time);}
                public static int cacheSize(){return SideTaskHost.SIDE_CONTEXT.size();}
                public static int count(String e){return (int)SideTaskHost.EVENTS.stream().filter(x->e.equals(x.get("event"))).count();}
                public static Map<String,Object> lastEvent(String e){return SideTaskHost.EVENTS.stream().filter(x->e.equals(x.get("event"))).reduce((a,b)->b).orElse(Map.of());}
                public static String reply(UUID id,String expected,String reason){var a=new JsonObject();a.addProperty("expected_type",expected);a.addProperty("reason",reason);var reply=new String[1];new RddDeclineSideTool().onServerCall("wire",a,RddCompanionBody.ALL.get(id),s->reply[0]=s);return reply[0];}
                public static String description(){return new RddDeclineSideTool().description();}
                public static void clearStops(){com.dwinovo.numen.task.CompanionTickDispatcher.stops=0;}
                public static int stops(){return com.dwinovo.numen.task.CompanionTickDispatcher.stops;}
            }
            """),
        Map.entry("com.dwinovo.numen.entity.NumenPlayer","""
            package com.dwinovo.numen.entity;
            import java.util.*;
            public class NumenPlayer {
                public final UUID id;public int hunger,hp,edible,hurtTime;public long time=1000;public String world="minecraft:overworld";public boolean available=true;
                public NumenPlayer(UUID id){this.id=id;}public UUID getUUID(){return id;}
                public boolean isSleeping(){return false;}public Level level(){return available?new Level():null;}
                public class Level {public Dimension dimension(){return new Dimension();}public long getDayTime(){return time;}}
                public class Dimension {public String location(){return world;}}
            }
            """),
        Map.entry("com.dwinovo.numen.plugins.rdd.RddCompanionBody","""
            package com.dwinovo.numen.plugins.rdd;import java.util.*;import com.dwinovo.numen.entity.NumenPlayer;
            class RddCompanionBody {static final Map<UUID,NumenPlayer> ALL=new HashMap<>();static NumenPlayer get(UUID id){return ALL.get(id);}}
            """),
        Map.entry("com.dwinovo.numen.plugins.rdd.RddPlugin","""
            package com.dwinovo.numen.plugins.rdd;import java.util.*;
            class RddPlugin {static Body bodyState(UUID id){return null;}static void clearBody(UUID id){}record Body(String subtaskId){}}
            """),
        Map.entry("com.dwinovo.numen.plugins.rdd.RddRepairDispatch","package com.dwinovo.numen.plugins.rdd;import java.util.*;class RddRepairDispatch {static Object lastRepair(UUID id){return null;}}"),
        Map.entry("com.dwinovo.numen.plugins.rdd.RddReplanTransition","package com.dwinovo.numen.plugins.rdd;import java.util.*;import com.dwinovo.numen.rdd.side.*;class RddReplanTransition {static boolean deferPlannerDaily(UUID id,MainlineResumeToken token){return false;}}"),
        Map.entry("com.dwinovo.numen.plugins.rdd.RddMonitor","package com.dwinovo.numen.plugins.rdd;import java.util.*;class RddMonitor {static void publish(String e,Map<String,Object> data){}}"),
        Map.entry("com.dwinovo.numen.plugins.rdd.SleepWakeWatcher","package com.dwinovo.numen.plugins.rdd;class SleepWakeWatcher {static void clearAll(){}}"),
        Map.entry("net.minecraft.server.MinecraftServer","package net.minecraft.server;public class MinecraftServer {}"),
        Map.entry("com.dwinovo.numen.task.CompanionTickDispatcher","""
            package com.dwinovo.numen.task;import java.util.*;import com.dwinovo.numen.entity.NumenPlayer;
            public class CompanionTickDispatcher {public static int stops;public static void stopActive(NumenPlayer p,String reason){stops++;}public static TaskRecord currentTaskFor(UUID id){return null;}}
            """),
        Map.entry("com.dwinovo.numen.task.TaskRecord","package com.dwinovo.numen.task;public class TaskRecord {public boolean isExternalCall(){return true;}public String getToolName(){return \"external\";}public String publicId(){return \"external\";}}"),
        Map.entry("com.dwinovo.numen.api.NumenPlugins","package com.dwinovo.numen.api;import com.dwinovo.numen.entity.NumenPlayer;import com.dwinovo.numen.task.TaskRecord;public class NumenPlugins {public static boolean interruptForSleep(NumenPlayer p,TaskRecord r){return false;}}"),
        Map.entry("com.dwinovo.numen.agent.tool.NumenTool","package com.dwinovo.numen.agent.tool;import java.util.*;import java.util.function.Consumer;import com.google.gson.JsonObject;import com.dwinovo.numen.entity.NumenPlayer;public interface NumenTool {String name();String description();Map<String,Object> parameterSchema();void onServerCall(String id,JsonObject args,NumenPlayer p,Consumer<String> reply);}"),
        Map.entry("com.dwinovo.numen.agent.tool.Schema","package com.dwinovo.numen.agent.tool;import java.util.*;public class Schema {public static Schema object(){return new Schema();}public Schema string(String k,String d){return this;}public Map<String,Object> build(){return Map.of();}}"),
        Map.entry("com.dwinovo.numen.task.TaskResult","package com.dwinovo.numen.task;import java.util.*;import com.google.gson.Gson;public record TaskResult(boolean success,String message,Map<String,Object> data){public static TaskResult ok(String m,Map<String,Object>d){return new TaskResult(true,m,d);}public static TaskResult fail(String m){return new TaskResult(false,m,Map.of());}public String toJson(){return new Gson().toJson(this);}}")
    );
}
