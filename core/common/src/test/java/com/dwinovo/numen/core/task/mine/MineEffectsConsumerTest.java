package com.dwinovo.numen.core.task.mine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Exact production onTick/dig/drop/count/stall/result methods; MC and navigation are boundaries.
 * Does not claim vanilla pickup, real A*, dispatch, cleanup, transport or game coverage.
 * In particular, a supplied FAILED route is not evidence that the real pathfinder rejects a route.
 */
class MineEffectsConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;
    static Class<?> fixture;

    @BeforeAll static void compileConsumer() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("core/common/src/main/java"))) root = root.getParent();
        assertNotNull(root);
        Path main = root.resolve("core/common/src/main/java/com/dwinovo/numen/core");
        String source = Files.readString(main.resolve("task/mine/MineCompanionTask.java"));
        String methods = "";
        for (String signature : List.of(
                "    protected TaskState onTick() {", "    private void mineProgress(BlockPos pos) {",
                "    private void clearNoShot() {", "    private int inventoryMatch() {",
                "    private List<BlockPos> droppedItems() {", "    private boolean nearKnownOre(BlockPos p) {",
                "    private void noteProgress() {", "    private TaskState stalledOut() {",
                "    protected Map<String, Object> resultData() {")) {
            methods += MineHarvestRecoveryConsumerTest.method(source, signature) + "
";
        }
        Path src = temp.resolve("src"), out = temp.resolve("classes");
        Files.createDirectories(out);
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-d", out.toString()));
        MineHarvestRecoveryConsumerTest.add(src, args, "com/dwinovo/numen/core/Constants.java", """
                package com.dwinovo.numen.core;
                public class Constants {
                    public static final Log LOG = new Log();
                    public static class Log { public void info(String s,Object... a){} public void debug(String s,Object... a){} }
                }
                """);
        MineHarvestRecoveryConsumerTest.add(src, args, "fixture/Effects.java", BOUNDARIES.replace("PRODUCTION_METHODS", methods));
        MineHarvestRecoveryConsumerTest.add(src, args, "fixture/PartialGather.java",
                Files.readString(main.resolve("task/mine/PartialGather.java")).replace("package com.dwinovo.numen.core.task.mine;", "package fixture;"));
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, diagnostics, diagnostics, args.toArray(String[]::new)),
                "fixture compiler failure is not a product red: " + diagnostics);
        loader = new URLClassLoader(new URL[]{out.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        fixture = loader.loadClass("fixture.Effects");
    }

    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }
    @SuppressWarnings("unchecked") static Map<String,Object> run(String scenario) throws Exception {
        return (Map<String,Object>) fixture.getMethod("run", String.class).invoke(null, scenario);
    }
    @Test void freshMatchingPickupResetsStallClockBeforeNavigation() throws Exception {
        var r=run("freshPickup");
        assertEquals("RUNNING",r.get("state"),"new real inventory gain is progress, not a stalled partial exit");
        assertEquals(1,r.get("gathered")); assertEquals(400L,r.get("progressTick"));
        assertEquals(true,r.get("partial")); assertEquals(3,r.get("shortfall"));
    }
    @Test void matchingPickupIsRecomputedNotAddedAgain() throws Exception {
        var r=run("repeatPickup"); assertEquals(1,r.get("gathered"));
        assertEquals(400L,r.get("progressTick"),"unchanged inventory must not keep refreshing the stall timer");
        assertEquals("RUNNING",r.get("state"));
    }
    @Test void harvestableOreAndFailedRouteNeverBecomeProduce() throws Exception {
        var r=run("unreachable"); assertEquals("FAILED",r.get("state")); assertEquals(0,r.get("gathered"));
        assertEquals("NO_PATH",r.get("failure")); assertEquals(0,r.get("digCalls"));
    }
    @Test void brokenTargetWithoutPickupDoesNotCount() throws Exception {
        var r=run("broken"); assertEquals(1,r.get("broken")); assertEquals(0,r.get("gathered"));
        assertEquals(1,r.get("anticipated")); assertEquals("RUNNING",r.get("state"));
    }
    @Test void fullInventoryAndMatchingGroundDropDoNotCountAsPickup() throws Exception {
        var r=run("full"); assertEquals(0,r.get("gathered")); assertEquals(1,r.get("dropGoals"));
        assertEquals("FAILED",r.get("state")); assertEquals(true,r.get("partial"));
    }
    @Test void wrongItemsAreNeitherProduceNorPickupGoals() throws Exception {
        var r=run("wrong"); assertEquals(0,r.get("gathered")); assertEquals(0,r.get("dropGoals"));
    }
    @Test void planningWaitAndDetourAreNotAStall() throws Exception {
        var wait=run("planning"); assertEquals("RUNNING",wait.get("state")); assertEquals(1L,wait.get("progressTick"));
        var move=run("detour"); assertEquals("RUNNING",move.get("state")); assertEquals(400L,move.get("progressTick"));
    }
    @Test void normalStationaryDigIsNotMistakenForNavigationStall() throws Exception {
        var r=run("digging"); assertEquals("RUNNING",r.get("state")); assertEquals(1,r.get("digCalls"));
        assertEquals(0,r.get("gathered"));
    }
    @Test void requestedMatchingInventoryDeltaCompletesWithoutAnotherDig() throws Exception {
        var r=run("complete"); assertEquals("SUCCESS",r.get("state")); assertEquals(4,r.get("gathered"));
        assertEquals(false,r.get("partial")); assertEquals(0,r.get("shortfall")); assertEquals(0,r.get("digCalls"));
    }
    @Test void noLootProfileKeepsExistingBrokenTargetSemantics() throws Exception {
        var r=run("creative"); assertEquals("SUCCESS",r.get("state")); assertEquals(4,r.get("gathered"));
    }

    static final String BOUNDARIES = """
            package fixture;
            import java.util.*;
            public class Effects {
                enum TaskState { RUNNING, SUCCESS, FAILED }
                enum FailureType { NO_PATH }
                record BlockPos(int x,int y,int z) {
                    double distSqr(BlockPos p){return (x-p.x)*(x-p.x)+(y-p.y)*(y-p.y)+(z-p.z)*(z-p.z);}
                    BlockPos immutable(){return this;} int getY(){return y;} String toShortString(){return x+","+y+","+z;}
                }
                record Item(String id) {}
                record ItemStack(Item item,int count) {
                    boolean isEmpty(){return count==0;} Item getItem(){return item;} int getCount(){return count;}
                }
                static class Inventory { List<ItemStack> items=new ArrayList<>(); }
                record ItemEntity(BlockPos pos,ItemStack item) { ItemStack getItem(){return item;} BlockPos blockPosition(){return pos;} }
                static class AABB { AABB(BlockPos p){} AABB inflate(int r){return this;} }
                static class BlockState { boolean isAir(){return false;} }
                static class Level {
                    long time; List<ItemEntity> entities=new ArrayList<>();
                    long getGameTime(){return time;} BlockState getBlockState(BlockPos p){return new BlockState();}
                    List<ItemEntity> getEntitiesOfClass(Class<ItemEntity> c,AABB b){return entities;}
                }
                static class ServerLevel extends Level {
                    ServerLevel getServer(){return this;} ServerLevel getPlayerList(){return this;} int getViewDistance(){return 3;}
                }
                static class Player {
                    Level world=new Level(); Inventory inv=new Inventory(); BlockPos feet=new BlockPos(0,64,0); boolean loot=true;
                    Level level(){return world;} Inventory getInventory(){return inv;} BlockPos blockPosition(){return feet;}
                }
                record WorkProfile(boolean dropsLoot) { static WorkProfile of(Player p){return new WorkProfile(p.loot);} }
                static class NavProfiler { static long begin(){return 0;} static void end(String s,long t){} }
                static class PlayerNav {
                    enum State { RUNNING, ARRIVED, FAILED }
                    enum ContextProvider { TERRAFORM }
                    State state=State.RUNNING; boolean planning;
                    State tick(){return state;} void pause(){} boolean planningInFlight(){return planning;}
                    static PlayerNav toRevalidating(Player p,java.util.function.Supplier<Object> g,double s,
                            java.util.function.BooleanSupplier b,ContextProvider c){return new PlayerNav();}
                    static PlayerNav toGoal(Player p,java.util.function.Supplier<Object> g,double s,
                            java.util.function.BooleanSupplier b,ContextProvider c){return new PlayerNav();}
                }
                static class NavGoal { static Object runAway(BlockPos p,int y){return new Object();} }
                static class Digger {
                    enum Result { BROKE_TARGET, NO_SHOT, BROKE_OCCLUDER, PROGRESSING }
                    BlockPos current; Result result=Result.PROGRESSING; int calls;
                    BlockPos current(){return current;} void cancel(){current=null;}
                    Result digStep(BlockPos p){calls++;return result;}
                }
                static class Record {
                    int count=4,mined; String label="iron_ore"; long deadline;
                    int getMined(){return mined;} void setMined(int n){mined=n;}
                    long getDeadlineGameTime(){return deadline;} void extendDeadlineTo(long n){deadline=n;}
                }
                static final int STALL_TICKS=400, DROP_LOITER_TICKS=5, MAX_NO_SHOT_TICKS=20, MAX_BRANCH_TICKS=600;
                static final double STALL_MOVE=2, MINE_SPEED=1; static final boolean EXPLORE_FOR_BLOCKS=false;
                Player player=new Player(); Record r=new Record(); Digger digger=new Digger(); PlayerNav nav=new PlayerNav();
                List<BlockPos> knownOres=new ArrayList<>(),drops=List.of(); Set<BlockPos> unworkable=new HashSet<>();
                Set<Item> dropItems=Set.of(new Item("raw_iron")); Map<BlockPos,Long> anticipatedDrops=new HashMap<>();
                int baseline=10,brokenTargets,branchTicks,branchY,navReplanCooldown,noShotTicks;
                boolean navIsBranch,lastQueryComplete=true; BlockPos branchPoint,noShotPos,lastProgressPos=new BlockPos(0,64,0),reachable;
                long lastProgressTick; Object navigationFailure; String progressNote="done",failure="";
                void prune(){} void maybeQuery(){} void invalidateNavigationFailure(){}
                boolean reachable(BlockPos p){return true;} BlockPos reachableTarget(){return reachable;}
                void stopNav(){nav=null;navIsBranch=false;} Object oreFieldCompiled(){return new Object();}
                String nearestOreInfo(){return "ore";} TaskState retryFailedNavigation(){stopNav();return TaskState.RUNNING;}
                TaskState noOreFailure(){return TaskState.FAILED;} String navigationFailureSuffix(){return "";}
                void fail(String s,FailureType t){failure=t.name();}
            PRODUCTION_METHODS
                public static Map<String,Object> run(String scenario) {
                    Effects f=new Effects(); BlockPos ore=new BlockPos(8,64,0); f.knownOres.add(ore);
                    f.player.inv.items.add(new ItemStack(new Item("raw_iron"),10));
                    f.player.world.time=400;
                    switch(scenario) {
                        case "freshPickup", "repeatPickup" -> f.player.inv.items.set(0,new ItemStack(new Item("raw_iron"),11));
                        case "unreachable" -> f.nav.state=PlayerNav.State.FAILED;
                        case "broken" -> { f.reachable=ore; f.digger.result=Digger.Result.BROKE_TARGET; }
                        case "full" -> {
                            for(int i=1;i<36;i++) f.player.inv.items.add(new ItemStack(new Item("stone"),64));
                            f.player.world.entities.add(new ItemEntity(new BlockPos(20,64,0),new ItemStack(new Item("raw_iron"),1)));
                        }
                        case "wrong" -> {
                            f.player.inv.items.add(new ItemStack(new Item("rotten_flesh"),64));
                            f.player.world.entities.add(new ItemEntity(new BlockPos(20,64,0),new ItemStack(new Item("rotten_flesh"),1)));
                            f.player.world.time=1;
                        }
                        case "planning" -> f.nav.planning=true;
                        case "detour" -> f.player.feet=new BlockPos(-3,64,0);
                        case "digging" -> f.digger.current=ore;
                        case "complete" -> f.player.inv.items.set(0,new ItemStack(new Item("raw_iron"),14));
                        case "creative" -> { f.player.loot=false; f.brokenTargets=4; }
                        default -> throw new IllegalArgumentException(scenario);
                    }
                    TaskState state=f.onTick();
                    if(scenario.equals("broken")){f.reachable=null;f.player.world.time++;state=f.onTick();}
                    if(scenario.equals("repeatPickup")){f.player.world.time++;state=f.onTick();}
                    Map<String,Object> result=f.resultData(); result.put("state",state.name());
                    result.put("progressTick",f.lastProgressTick);result.put("broken",f.brokenTargets);
                    result.put("dropGoals",f.drops.size());result.put("anticipated",f.anticipatedDrops.size());
                    result.put("digCalls",f.digger.calls);result.put("failure",f.failure);return result;
                }
            }
            """;
}
