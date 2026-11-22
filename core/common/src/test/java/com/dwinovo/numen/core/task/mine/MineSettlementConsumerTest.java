package com.dwinovo.numen.core.task.mine;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Exact Mine tick, base tick/result and TaskSlot settlement methods; MC, record storage,
 * logger and cleanup I/O are boundaries. Not a full dispatcher/server/network test. */
class MineSettlementConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;
    static Class<?> fixture;
    @BeforeAll static void compileConsumer() throws Exception {
        Path root=Path.of("").toAbsolutePath();
        while(root!=null&&!Files.isDirectory(root.resolve("core/common/src/main/java")))root=root.getParent();
        assertNotNull(root);
        Path main=root.resolve("core/common/src/main/java/com/dwinovo/numen/core");
        String mine=Files.readString(main.resolve("task/mine/MineCompanionTask.java"));
        String base=Files.readString(main.resolve("task/base/AbstractCompanionTask.java"));
        String slot=Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/task/TaskSlot.java"));
        String methods="";
        for(String signature:List.of("    protected TaskState onTick() {", "    private void mineProgress(BlockPos pos) {",
                "    private void clearNoShot() {", "    private int inventoryMatch() {",
                "    private List<BlockPos> droppedItems() {", "    private boolean nearKnownOre(BlockPos p) {",
                "    private void noteProgress() {", "    private TaskState stalledOut() {",
                "    protected Map<String, Object> resultData() {", "    protected String successMessage() {",
                "    protected String timeoutMessage() {", "    protected String cancelledMessage() {"))
            methods+=MineHarvestRecoveryConsumerTest.method(mine,signature)+"\n";
        for(String signature:List.of("    public final TaskState tick(NumenPlayer companion) {",
                "    public final TaskResult result(TaskState finalState) {",
                "    protected Map<String, Object> resultData(TaskState finalState) {"))
            methods+=MineHarvestRecoveryConsumerTest.method(base,signature)+"\n";
        String slotMethods="";
        for(String signature:List.of("    void tick(NumenPlayer companion) {", "    void settleIfTerminal() {",
                "    private void settle() {", "    private void notifySettled(TaskRecord settled) {"))
            slotMethods+=MineHarvestRecoveryConsumerTest.method(slot,signature)+"\n";
        String boundaries=MineEffectsConsumerTest.BOUNDARIES
            .replace("import java.util.*;", "import java.util.*; import com.dwinovo.numen.task.TaskResult;")
            .replace("enum TaskState { RUNNING, SUCCESS, FAILED }", "enum TaskState { RUNNING, SUCCESS, FAILED, TIMEOUT, CANCELLED; boolean isTerminal(){return this!=RUNNING;} }")
            .replace("static class Player {", "static class NumenPlayer extends Player {} static class Player {")
            .replace("Player player=new Player();", "NumenPlayer player=new NumenPlayer();")
            .replace("static class Record {", "static class TaskRecord {")
            .replace("int count=4,mined; String label=\"iron_ore\"; long deadline;", RECORD)
            .replace("Record r=new Record();", "TaskRecord r=new TaskRecord();")
            .replace("PRODUCTION_METHODS", methods+HOST.replace("SLOT_METHODS",slotMethods));
        Path src=temp.resolve("src"),out=temp.resolve("classes");Files.createDirectories(out);
        List<String> args=new ArrayList<>(List.of("-encoding","UTF-8","-proc:none","-d",out.toString()));
        add(src,args,"fixture/Effects.java",boundaries);
        add(src,args,"fixture/PartialGather.java",Files.readString(main.resolve("task/mine/PartialGather.java")).replace("package com.dwinovo.numen.core.task.mine;","package fixture;"));
        add(src,args,"com/dwinovo/numen/task/TaskResult.java",Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/task/TaskResult.java")));
        add(src,args,"org/slf4j/LoggerFactory.java","package org.slf4j; public class LoggerFactory { public static LoggerFactory getLogger(Class<?> c){return new LoggerFactory();} public void info(String s,Object... a){} }");
        add(src,args,"com/dwinovo/numen/core/Constants.java","package com.dwinovo.numen.core; public class Constants {public static final Log LOG=new Log(); public static class Log {public void info(String s,Object... a){} public void debug(String s,Object... a){}}}");
        URL gson=com.google.gson.Gson.class.getProtectionDomain().getCodeSource().getLocation();
        args.addAll(List.of("-classpath",Path.of(gson.toURI()).toString()));
        ByteArrayOutputStream diagnostics=new ByteArrayOutputStream();
        assertEquals(0,ToolProvider.getSystemJavaCompiler().run(null,diagnostics,diagnostics,args.toArray(String[]::new)),"SETUP not business red: "+diagnostics);
        loader=new URLClassLoader(new URL[]{out.toUri().toURL(),gson},ClassLoader.getPlatformClassLoader());fixture=loader.loadClass("fixture.Effects");
    }
    static void add(Path src,List<String> args,String p,String s)throws Exception {MineHarvestRecoveryConsumerTest.add(src,args,p,s);}
    @AfterAll static void close()throws Exception{if(loader!=null)loader.close();}
    @SuppressWarnings("unchecked") static Map<String,Object> run(String s)throws Exception{return (Map<String,Object>)fixture.getMethod("settlement",String.class).invoke(null,s);}
    @Test void matchingPickupSettlesExactlyOnce()throws Exception{
        var r=run("complete");assertEquals("SUCCESS",r.get("state"));assertEquals(1,r.get("outbox"));assertEquals(1,r.get("notices"));
        assertEquals(1,r.get("cleanup"));assertEquals(1,r.get("taskTicks"));assertEquals(4,r.get("gathered"));assertEquals(false,r.get("partial"));assertEquals(true,r.get("slotEmpty"));
        var json=com.google.gson.JsonParser.parseString((String)r.get("json")).getAsJsonObject();assertTrue(json.get("success").getAsBoolean());assertEquals(4,json.getAsJsonObject("data").get("gathered").getAsInt());
    }
    @Test void partialPickupKeepsSlotThenExhaustionSettlesOnce()throws Exception{
        var r=run("partial");assertEquals("SUCCESS",r.get("state"));assertEquals(0,r.get("outboxAfterPickup"));assertEquals(1,r.get("outbox"));
        assertEquals(1,r.get("gathered"));assertEquals(true,r.get("partial"));assertEquals(3,r.get("shortfall"));assertEquals(2,r.get("taskTicks"));assertEquals(1,r.get("cleanup"));
    }
    @Test void exactDeadlinePrecedesMineTick()throws Exception{
        var r=run("deadline");assertEquals("TIMEOUT",r.get("state"));assertEquals(0,r.get("taskTicks"));assertEquals(0,r.get("gathered"));assertEquals(1,r.get("outbox"));assertEquals(1,r.get("cleanup"));
        var json=com.google.gson.JsonParser.parseString((String)r.get("json")).getAsJsonObject();assertFalse(json.get("success").getAsBoolean());assertTrue(json.get("timed_out").getAsBoolean());
    }
    @Test void beforeDeadlineAllowsCompletion()throws Exception{
        var r=run("beforeDeadline");assertEquals("SUCCESS",r.get("state"));assertEquals(4,r.get("gathered"));assertEquals(1,r.get("taskTicks"));assertEquals(1,r.get("outbox"));
    }
    static final String RECORD="""
        int count=4,mined; String label="iron_ore"; long deadline=1000; TaskState state=TaskState.RUNNING; TaskResult result;
        TaskState getState(){return state;} void setState(TaskState s){state=s;} void setResult(TaskResult r){result=r;}
        String publicId(){return "mine-1";} String getToolName(){return "mine";}
        """;
    static final String HOST="""
        TaskState pendingTerminal; String doneReason="done"; int cleanups,taskTicks;
        static class Journey {boolean isEmpty(){return true;} String describe(){return "";}}
        Journey journey=new Journey(); void crashed(String where,RuntimeException e){throw e;}
        void cleanup(){cleanups++;stopNav();}
        interface Task {TaskState tick(NumenPlayer p);TaskResult result(TaskState state);}
        static class TaskSlot {
            TaskRecord record; Task task; int ticksRun; java.util.function.Consumer<TaskRecord> outbox,onSettled;
        SLOT_METHODS
        }
        public static Map<String,Object> settlement(String scenario){
            Effects f=new Effects();f.knownOres.add(new BlockPos(8,64,0));f.player.inv.items.add(new ItemStack(new Item("raw_iron"),scenario.equals("partial")?11:14));
            f.player.world.time=400;if(scenario.equals("deadline"))f.r.deadline=400;if(scenario.equals("beforeDeadline"))f.r.deadline=401;
            TaskSlot slot=new TaskSlot();slot.record=f.r;slot.task=new Task(){public TaskState tick(NumenPlayer p){f.taskTicks++;return f.tick(p);}public TaskResult result(TaskState s){return f.result(s);}};
            List<TaskRecord> out=new ArrayList<>();int[] notices={0};slot.outbox=out::add;slot.onSettled=r->notices[0]++;
            slot.tick(f.player);int first=out.size();if(scenario.equals("partial")){f.player.world.time=800;slot.tick(f.player);}
            slot.tick(f.player);slot.settleIfTerminal();slot.tick(f.player);
            Map<String,Object> result=new HashMap<>(f.r.result.data());result.put("state",f.r.state.name());result.put("outbox",out.size());result.put("outboxAfterPickup",first);
            result.put("notices",notices[0]);result.put("cleanup",f.cleanups);result.put("taskTicks",f.taskTicks);result.put("slotEmpty",slot.record==null&&slot.task==null);result.put("json",f.r.result.toJson());return result;
        }
        """;
}
