package com.dwinovo.numen.core.task.mine;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * V06 recovery regression (2026-11-12): a rejected mine suggested equipping a
 * stone pick, then rejected the same request after that equip succeeded.
 * Compiles the exact production preconditions() and canHarvest() methods and
 * noOreFailure() and production Precondition/FailureType/TaskState definitions, then consumes the returned
 * Failure. No copied harvest algorithm or production-message snapshot.
 * The late branch is a source-confirmed sibling defect, not a second V06 game incident.
 * MC boundaries supply tool/drop eligibility, inventory slots, remembered targets and WorkProfile;
 * vanilla tier tags, actual digging, async transport and model behavior are not
 * simulated. This keeps the failed recovery test independent of MC bootstrap;
 * a source/fixture compiler failure is a setup error, never a business red.
 */
class MineHarvestRecoveryConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;
    static Class<?> fixture, navigationFixture;

    @BeforeAll static void compileProductionMethods() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("core/common/src/main/java"))) {
            root = root.getParent();
        }
        assertNotNull(root, "must locate the candidate source tree");
        Path main = root.resolve("core/common/src/main/java/com/dwinovo/numen/core");
        String gate = method(Files.readString(main.resolve("task/mine/MineCompanionTask.java")),
                "    protected List<Precondition> preconditions() {");
        String harvest = method(Files.readString(main.resolve("pathing/util/BlockHelper.java")),
                "    public static boolean canHarvest(Container inv, BlockState state) {");
        String late = method(Files.readString(main.resolve("task/mine/MineCompanionTask.java")),
                "    private TaskState noOreFailure() {");
        Path src = temp.resolve("src"), out = temp.resolve("classes");
        Files.createDirectories(out);
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-d", out.toString()));
        add(src, args, "com/dwinovo/numen/core/task/base/Precondition.java",
                Files.readString(main.resolve("task/base/Precondition.java")));
        add(src, args, "com/dwinovo/numen/core/FailureType.java",
                Files.readString(main.resolve("FailureType.java")));
        add(src, args, "com/dwinovo/numen/task/TaskState.java",
                Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/task/TaskState.java")));
        add(src, args, "fixture/HarvestFixture.java", """
                package fixture;
                import java.util.*;
                import com.dwinovo.numen.core.FailureType;
                import com.dwinovo.numen.core.task.base.Precondition;
                import com.dwinovo.numen.task.TaskState;
                public class HarvestFixture {
                    // Boundary data: eligibility is supplied, not calculated from a tier table.
                    static class BlockState {
                        final String key; final boolean gated;
                        BlockState(String key, boolean gated) { this.key=key; this.gated=gated; }
                        boolean requiresCorrectToolForDrops() { return gated; }
                    }
                    static class ItemStack {
                        final Set<String> eligible;
                        ItemStack(Set<String> eligible) { this.eligible=eligible; }
                        boolean isCorrectToolForDrops(BlockState s) { return eligible.contains(s.key); }
                    }
                    static class Container {
                        final ItemStack[] slots = new ItemStack[36]; int reads;
                        Container() { Arrays.fill(slots, new ItemStack(Set.of())); }
                        int getContainerSize() { return slots.length; }
                        ItemStack getItem(int i) { reads++; return slots[i]; }
                    }
                    static class Block {
                        final BlockState state;
                        Block(String key, boolean gated) { state=new BlockState(key,gated); }
                        BlockState defaultBlockState() { return state; }
                    }
                    static class Player {
                        final Container inventory=new Container(); boolean creative;
                        Container getInventory() { return inventory; }
                    }
                    static class WorkProfile {
                        final Player player;
                        WorkProfile(Player player) { this.player=player; }
                        static WorkProfile of(Player player) { return new WorkProfile(player); }
                        boolean instaBreak() { return player.creative; }
                    }
                    static class BlockHelper {
                HARVEST_METHOD
                    }
                    static class Request {
                        List<Block> targets; String label="diamond_ore+1"; int mined;
                        int getMined() { return mined; }
                    }
                    final Player player; final Request r;
                    final Set<Integer> unharvestable=new HashSet<>(), unworkable=new HashSet<>();
                    Precondition.Failure terminal; int failureCalls;
                    void fail(String message, FailureType type) {
                        terminal=new Precondition.Failure(message,type); failureCalls++;
                    }
                    HarvestFixture(Player player, Request r) { this.player=player; this.r=r; }
                PRECONDITIONS_METHOD
                LATE_METHOD
                    public static Map<String,Object> runLate(String scenario, int gathered) {
                        Request request=new Request(); request.mined=gathered;
                        HarvestFixture task=new HarvestFixture(new Player(),request);
                        switch(scenario) {
                            // Boundary: prune's remembered target sets, not a second prune algorithm.
                            case "unharvestable" -> task.unharvestable.addAll(Set.of(1,2));
                            case "unworkable" -> task.unworkable.add(1);
                            case "empty" -> { }
                            default -> throw new IllegalArgumentException(scenario);
                        }
                        TaskState state=task.noOreFailure();
                        return Map.of("state",state.name(), "type",task.terminal.type().name(),
                                "message",task.terminal.message(), "failureCalls",task.failureCalls);
                    }
                    public static Map<String,Object> run(String scenario, int slot) {
                        Player player=new Player(); Request request=new Request();
                        Block high=new Block("high",true), low=new Block("low",true);
                        request.targets=List.of(high);
                        switch(scenario) {
                            case "insufficient" -> player.inventory.slots[slot]=new ItemStack(Set.of("low"));
                            case "correct" -> player.inventory.slots[slot]=new ItemStack(Set.of("high"));
                            case "mixed", "mixedReverse" -> {
                                player.inventory.slots[slot]=new ItemStack(Set.of("low"));
                                request.targets=scenario.equals("mixed") ? List.of(high,low) : List.of(low,high);
                            }
                            case "allUnharvestable" -> request.targets=List.of(high,low);
                            case "creative" -> player.creative=true;
                            case "noToolRequired" -> request.targets=List.of(new Block("free",false));
                            case "empty" -> { }
                            default -> throw new IllegalArgumentException(scenario);
                        }
                        List<Precondition> gates=new HarvestFixture(player,request).preconditions();
                        if(gates.size()!=1) throw new AssertionError("unexpected precondition count");
                        Precondition.Failure failure=gates.get(0).check();
                        return Map.of("accepted", failure==null,
                                "type", failure==null ? "" : failure.type().name(),
                                "message", failure==null ? "" : failure.message(),
                                "reads", player.inventory.reads);
                    }
                }
                """.replace("HARVEST_METHOD", harvest).replace("PRECONDITIONS_METHOD", gate).replace("LATE_METHOD", late));
        addNavigationFixture(main, src, args);
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "JDK compiler is required for the real-method consumer");
        var diagnostics = new ByteArrayOutputStream();
        int code = compiler.run(null, diagnostics, diagnostics, args.toArray(String[]::new));
        assertEquals(0, code, () -> "production slice must compile before behavior assertions: "
                + diagnostics.toString(StandardCharsets.UTF_8));
        loader = new URLClassLoader(new URL[]{out.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        fixture = loader.loadClass("fixture.HarvestFixture");
        navigationFixture = loader.loadClass("fixture.NavigationFixture");
    }

    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void insufficientToolInAnyInventoryRegionIsStillWrongTool() throws Exception {
        for (int slot : List.of(0, 8, 9, 35)) {
            var result = run("insufficient", slot);
            assertWrongTool(result);
            assertEquals(36, result.get("reads"), "must consult all inventory slots, slot=" + slot);
        }
    }

    @Test void movingTheSameInsufficientToolIntoTheHandCannotResolveTheFailure() throws Exception {
        var stowed = run("insufficient", 35);
        var held = run("insufficient", 0);
        assertWrongTool(stowed);
        assertWrongTool(held);
        assertEquals(stowed.get("message"), held.get("message"));
        String message = (String) held.get("message");
        assertFalse(message.contains("equip_item"), "re-equipping cannot fix this inventory predicate: " + message);
        assertFalse(message.contains("stone_pickaxe"), "do not advertise an insufficient tier as a generic fix");
    }

    @Test void validToolAnywhereInTheBackpackNeedsNoManualEquip() throws Exception {
        for (int slot : List.of(0, 8, 9, 35)) {
            var result = run("correct", slot);
            assertEquals(true, result.get("accepted"), "correct tool in slot=" + slot);
            assertEquals(slot + 1, result.get("reads"));
        }
    }

    @Test void mixedRequestKeepsAnyHarvestableSemanticsInEitherOrder() throws Exception {
        assertEquals(true, run("mixed", 35).get("accepted"));
        assertEquals(true, run("mixedReverse", 35).get("accepted"));
    }

    @Test void noCorrectToolForAnyRequestedTargetStillRejects() throws Exception {
        assertWrongTool(run("empty", 0));
        assertWrongTool(run("allUnharvestable", 0));
    }

    @Test void instabreakProfilePreservesTheOriginalToolGateExemption() throws Exception {
        var result = run("creative", 0);
        assertEquals(true, result.get("accepted"));
        assertEquals(0, result.get("reads"), "instabreak must bypass the inventory check");
    }

    @Test void blocksWhoseDropsNeedNoToolStillPass() throws Exception {
        var result = run("noToolRequired", 0);
        assertEquals(true, result.get("accepted"));
        assertEquals(0, result.get("reads"));
    }

    @Test void realFailureExplainsMissingCapabilityAndHowToRecover() throws Exception {
        var result = run("insufficient", 35);
        assertWrongTool(result);
        String message = ((String) result.get("message")).toLowerCase(java.util.Locale.ROOT);
        assertTrue(message.contains("diamond_ore+1"), "retain the actual requested label");
        assertTrue(message.contains("inventory"), "explain the whole-inventory prerequisite: " + message);
        assertTrue(message.contains("type") && message.contains("tier"), "required tool kind and tier matter: " + message);
        assertTrue(message.contains("obtain") && message.contains("retry"), "acquire a qualifying tool before retrying: " + message);
        assertTrue(message.contains("same") && message.contains("will not"), "distinguish acquisition from moving the same tool: " + message);
        assertFalse(message.contains("break_block"), "do not suggest bypassing the no-drop refusal");
    }

    @Test void lateUnharvestableKeepsFailedWrongToolAndGatheredCount() throws Exception {
        var result = runLate("unharvestable", 4);
        assertEquals("FAILED", result.get("state"));
        assertEquals("WRONG_TOOL", result.get("type"));
        assertEquals(1, result.get("failureCalls"));
        String message = (String) result.get("message");
        assertTrue(message.startsWith("found 2 diamond_ore+1"), message);
        assertTrue(message.contains("gathered 4"), message);
    }

    @Test void lateRecoveryAlsoRequiresAcquisitionInsteadOfEquippingTheSameTool() throws Exception {
        var result = runLate("unharvestable", 4);
        assertEquals("FAILED", result.get("state"));
        assertEquals("WRONG_TOOL", result.get("type"));
        String message = ((String) result.get("message")).toLowerCase(java.util.Locale.ROOT);
        assertFalse(message.contains("equip_item"), message);
        assertFalse(message.contains("break_block"), message);
        assertTrue(message.contains("inventory"), message);
        assertTrue(message.contains("type") && message.contains("tier"), message);
        assertTrue(message.contains("obtain") && message.contains("retry"), message);
        assertTrue(message.contains("same") && message.contains("will not"), message);
    }

    @Test void lateUnworkableAndEmptyRetainTheirExistingReasons() throws Exception {
        var unworkable = runLate("unworkable", 0);
        assertEquals("FAILED", unworkable.get("state"));
        assertEquals("NO_PATH", unworkable.get("type"));
        assertEquals(1, unworkable.get("failureCalls"));
        assertEquals("found 1 diamond_ore+1 nearby but no clear shot at any of them from any stance I could take; gathered 0",
                unworkable.get("message"));
        var empty = runLate("empty", 0);
        assertEquals("FAILED", empty.get("state"));
        assertEquals("MINED_OUT", empty.get("type"));
        assertEquals(1, empty.get("failureCalls"));
        assertEquals("no reachable diamond_ore+1 found in the loaded area around me", empty.get("message"));
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> runLate(String scenario, int gathered) throws Exception {
        return (Map<String,Object>) fixture.getMethod("runLate", String.class, int.class).invoke(null, scenario, gathered);
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> run(String scenario, int slot) throws Exception {
        return (Map<String,Object>) fixture.getMethod("run", String.class, int.class).invoke(null, scenario, slot);
    }


    // Compile the real retry, tick, progress, suspend and terminal consumers, not a copied policy.
    static void addNavigationFixture(Path main, Path src, List<String> args) throws Exception {
        // Red control may point only this consumer at the frozen pre-fix production file.
        String control = System.getenv("RDD_MINE_NAV_SOURCE");
        Path sourcePath = control == null ? main.resolve("task/mine/MineCompanionTask.java") : Path.of(control);
        String source = Files.readString(sourcePath);
        System.out.println("mine navigation consumer source=" + sourcePath + " sha256="
                + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(source.getBytes(StandardCharsets.UTF_8))));
        StringBuilder methods = new StringBuilder();
        for (String signature : List.of("    protected TaskState onTick() {",
                "    private TaskState retryFailedNavigation() {", "    private void invalidateNavigationFailure() {",
                "    private String navigationFailureSuffix() {", "    private void noteProgress() {",
                "    private TaskState stalledOut() {", "    private void mineProgress(BlockPos pos) {",
                "    private void clearNoShot() {", "    protected Map<String, Object> resultData() {",
                "    protected String successMessage() {")) {
            if (source.contains(signature)) methods.append(method(source, signature)).append("\n");
        }
        String stopSignature = "    public void stop(NumenPlayer companion, StopReason why) {";
        String baseStop = method(Files.readString(main.resolve("task/base/AbstractCompanionTask.java")), stopSignature);
        if (source.contains(stopSignature)) methods.append(method(source, stopSignature));
        int recordStart = source.indexOf("    private record NavigationFailure(");
        int recordEnd = source.indexOf("\n    }", recordStart) + "\n    }".length();
        String record = recordStart < 0 ? "" : source.substring(recordStart, recordEnd)
                + "\n    NavigationFailure navigationFailure;";
        add(src, args, "com/dwinovo/numen/core/task/mine/NoPathVerdict.java",
                Files.readString(main.resolve("task/mine/NoPathVerdict.java")));
        add(src, args, "com/dwinovo/numen/core/task/mine/PartialGather.java",
                Files.readString(main.resolve("task/mine/PartialGather.java")));
        add(src, args, "com/dwinovo/numen/core/Constants.java", """
                package com.dwinovo.numen.core;
                public class Constants {
                    public static final Log LOG=new Log();
                    public static class Log { public void info(String m,Object... args){} public void debug(String m,Object... args){} }
                }
                """);
        add(src, args, "fixture/NavigationFixture.java", """
                package fixture;
                import java.util.*;
                import com.dwinovo.numen.core.FailureType;
                import com.dwinovo.numen.core.task.base.Precondition;
                import com.dwinovo.numen.core.task.mine.NoPathVerdict;
                import com.dwinovo.numen.core.task.mine.PartialGather;
                import com.dwinovo.numen.task.TaskState;
                import fixture.NavigationFixture.NumenPlayer;
                import fixture.NavigationFixture.StopReason;
                import fixture.NavigationFixture.InputDriver;
                class SuspendBoundary {
                    final NumenPlayer player = new NumenPlayer();
                BASE_STOP
                }
                public class NavigationFixture extends SuspendBoundary {
                    record BlockPos(int x,int y,int z) {
                        double distSqr(BlockPos p) { return (x-p.x)*(x-p.x)+(y-p.y)*(y-p.y)+(z-p.z)*(z-p.z); }
                        String toShortString(){return x+", "+y+", "+z;} BlockPos immutable(){return this;} int getY(){return y;}
                    }
                    static class Level { long now; BlockState getBlockState(BlockPos p){return new BlockState();} long getGameTime(){return now;} }
                    static class BlockState { boolean isAir(){return false;} Object getBlock(){return "ore";} }
                    static class NumenPlayer { BlockPos feet=new BlockPos(0,64,0); Level level=new Level(); int halts; boolean shifting=true;
                        Level level(){return level;} BlockPos blockPosition(){return feet;} void setShiftKeyDown(boolean b){shifting=b;} }
                    static class InputDriver { static void halt(NumenPlayer p){p.halts++;} }
                    enum StopReason { PREEMPTED }
                    enum NavState { RUNNING, ARRIVED, FAILED }
                    static class PlayerNav {
                        String reason; boolean planning,paused; NavState state=NavState.FAILED;
                        PlayerNav(String reason){this.reason=reason;} String failReason(){return reason;}
                        FailureType failType(){return FailureType.NO_PATH;} boolean planningInFlight(){return planning;}
                        NavState tick(){return state;} void pause(){paused=true;}
                        enum ContextProvider { TERRAFORM }
                        static PlayerNav toRevalidating(NumenPlayer p,java.util.function.Supplier<Object> g,double s,
                                java.util.function.BooleanSupplier b,ContextProvider c){return new PlayerNav("new route");}
                        static PlayerNav toGoal(NumenPlayer p,java.util.function.Supplier<Object> g,double s,
                                java.util.function.BooleanSupplier b,ContextProvider c){return new PlayerNav("branch");}
                    }
                    static class NavGoal { static Object runAway(BlockPos p,int y){return null;} }
                    static class NavProfiler { static long begin(){return 0;} static void end(String s,long t){} }
                    static class WorkProfile { static WorkProfile of(NumenPlayer p){return new WorkProfile();} boolean dropsLoot(){return true;} }
                    static class Request {
                        int count=5,mined; String label="stone+4"; long deadline=3000;
                        Set<Object> targets=Set.of("ore"); boolean containsTarget(BlockPos p){return true;}
                        int getMined(){return mined;} void setMined(int n){mined=n;}
                        long getDeadlineGameTime(){return deadline;} void extendDeadlineTo(long n){deadline=n;}
                    }
                    static class Digger {
                        enum Result { BROKE_TARGET, BROKE_OCCLUDER, NO_SHOT, PROGRESSING }
                        Result next=Result.BROKE_TARGET; BlockPos current(){return null;}
                        Result digStep(BlockPos p){return next;} void cancel(){}
                    }
                    final Request r=new Request(); final Digger digger=new Digger();
                    final List<BlockPos> knownOres=new ArrayList<>(List.of(new BlockPos(3,57,4)));
                    final Set<BlockPos> unworkable=new HashSet<>(); final Map<BlockPos,Long> anticipatedDrops=new HashMap<>();
                    List<BlockPos> drops=List.of(), observedDrops=List.of(); PlayerNav nav;
                    boolean lastQueryComplete=true,navIsBranch; int coldMapFails,queryCooldown,navReplanCooldown;
                    int baseline,brokenTargets,branchTicks,branchY,noShotTicks,pickup; long lastProgressTick;
                    BlockPos lastProgressPos=player.feet,branchPoint,noShotPos,reachable,latchedMineTarget;
                    String progressNote="done"; Precondition.Failure terminal;
                    static final int STALL_TICKS=400,NAV_REPLAN_MIN_GAP_TICKS=20,MAX_NO_SHOT_TICKS=20,MAX_BRANCH_TICKS=600,DROP_LOITER_TICKS=5;
                    static final double STALL_MOVE=2.0,MINE_SPEED=1.0; static final boolean EXPLORE_FOR_BLOCKS=false;
                    void fail(String m,FailureType t){terminal=new Precondition.Failure(m,t);}
                    void stopNav(){nav=null;navIsBranch=false;} String nearestOreInfo(){return "boundary ore";}
                    void prune(){} void maybeQuery(){} Object oreFieldCompiled(){return null;}
                    int inventoryMatch(){return pickup;} List<BlockPos> droppedItems(){return observedDrops;}
                    BlockPos reachableTarget(){return reachable;} boolean reachable(BlockPos p){return true;}
                    TaskState noOreFailure(){fail("empty",FailureType.MINED_OUT);return TaskState.FAILED;}
                RECORD
                METHODS
                    public static Map<String,Object> run(String scenario) {
                        NavigationFixture t=new NavigationFixture();
                        if(scenario.equals("reorder")) t.knownOres.add(new BlockPos(5,57,6));
                        if(scenario.equals("duplicateDrop")) t.observedDrops=List.of(new BlockPos(1,64,1));
                        t.nav=new PlayerNav("scaffolding list is EMPTY; carrying dirt x6");
                        TaskState first=t.onTick();
                        if(first!=TaskState.RUNNING || t.navReplanCooldown!=20) throw new AssertionError("retry semantics");
                        switch(scenario) {
                            case "same" -> { }
                            case "reorder" -> {Collections.reverse(t.knownOres);t.onTick();}
                            case "duplicateDrop" -> {t.observedDrops=List.of(new BlockPos(1,64,1),new BlockPos(1,64,1));t.onTick();}
                            case "replace" -> {t.nav=new PlayerNav("blocked by liquid");t.onTick();}
                            case "blank" -> {t.nav=new PlayerNav(" ");t.onTick();}
                            case "cold" -> {t.lastQueryComplete=false;t.nav=new PlayerNav("cold reason");t.onTick();t.lastQueryComplete=true;}
                            case "coldLimit" -> {t.lastQueryComplete=false;t.coldMapFails=39;t.nav=new PlayerNav("cold limit");t.onTick();t.lastQueryComplete=true;}
                            case "move" -> {t.player.feet=new BlockPos(1,64,0);t.onTick();t.player.feet=t.lastProgressPos;}
                            case "target" -> {t.knownOres.add(new BlockPos(9,57,9));t.onTick();t.knownOres.remove(1);}
                            case "drop" -> {t.observedDrops=List.of(new BlockPos(1,64,1));t.onTick();t.observedDrops=List.of();t.onTick();}
                            case "break" -> t.mineProgress(t.knownOres.get(0));
                            case "occluder" -> {t.digger.next=Digger.Result.BROKE_OCCLUDER;t.mineProgress(t.knownOres.get(0));}
                            case "pickup" -> {t.pickup=1;t.onTick();t.pickup=0;t.r.setMined(0);}
                            case "pause" -> {t.nav=new PlayerNav("warm plan");t.stop(t.player,StopReason.PREEMPTED);}
                            case "pauseMoved" -> {t.stop(t.player,StopReason.PREEMPTED);t.player.feet=new BlockPos(1,64,0);t.onTick();t.player.feet=t.lastProgressPos;}
                            case "arrive" -> {t.nav=new PlayerNav("old");t.nav.state=NavState.ARRIVED;t.reachable=new BlockPos(4,64,0);}
                            case "partial" -> t.r.setMined(2);
                            case "planning" -> {t.nav=new PlayerNav("pending");t.nav.planning=true;}
                            case "fresh" -> t=new NavigationFixture();
                            default -> throw new IllegalArgumentException(scenario);
                        }
                        if(scenario.equals("arrive")) {t.reachable=null;t.onTick();}
                        if(scenario.equals("break")) t.knownOres.add(new BlockPos(3,57,4));
                        t.player.level.now=400;
                        TaskState end=t.stalledOut();
                        Map<String,Object> out=new HashMap<>(t.resultData());
                        out.put("state",end==null ? "RUNNING" : end.name());
                        out.put("message",t.terminal==null ? "" : t.terminal.message());
                        out.put("type",t.terminal==null ? "" : t.terminal.type().name());
                        out.put("stops",t.player.halts);out.put("navPreserved",t.nav!=null);out.put("shifting",t.player.shifting);
                        out.put("progressTick",t.lastProgressTick);
                        return out;
                    }
                }
                """.replace("BASE_STOP",baseStop).replace("RECORD",record).replace("METHODS",methods));
    }

    @Test void stalledMineDeliversLatestConstraintWithOriginalFailureAndRecovery() throws Exception {
        var result=runNavigation("same");
        assertEquals("FAILED",result.get("state"));assertEquals("NO_PATH",result.get("type"));
        String message=(String)result.get("message");
        assertTrue(message.contains("gathered 0") && message.contains("goto a surface opening"));
        assertTrue(message.contains("break_block") && message.contains("scaffolding list is EMPTY"));
        assertTrue(message.contains("historical diagnostic") && message.contains("permissions may have changed"));
        assertEquals(false,result.get("navPreserved"));
    }

    @Test void sameGoalMembershipSurvivesOrderingAndDuplicateDropPositions() throws Exception {
        for (String scenario : List.of("reorder", "duplicateDrop")) {
            assertTrue(((String) runNavigation(scenario).get("message")).contains("scaffolding list is EMPTY"), scenario);
        }
    }

    @Test void newerFailureReplacesOldConstraintAndBlankDoesNotResurrectIt() throws Exception {
        String newer=(String)runNavigation("replace").get("message");
        assertTrue(newer.contains("blocked by liquid"));assertFalse(newer.contains("EMPTY"));
        assertFalse(((String)runNavigation("blank").get("message")).contains("Earlier navigation attempt failed"));
    }

    @Test void changedContextNeverLeaksAnOldConstraintEvenAfterReturning() throws Exception {
        for(String scenario:List.of("cold","coldLimit","move","target","drop","break","occluder","pickup","pauseMoved","arrive","fresh")) {
            var result=runNavigation(scenario);
            assertEquals("FAILED",result.get("state"),scenario);
            assertFalse(((String)result.get("message")).contains("Earlier navigation attempt failed"),scenario);
            assertEquals("NO_PATH",result.get("type"),scenario);
        }
    }

    @Test void pauseNeverReplaysAnOldDiagnosticAndKeepsTheWarmPlan() throws Exception {
        var pause=runNavigation("pause");
        assertEquals(1,pause.get("stops"));assertEquals(true,pause.get("navPreserved"));assertEquals(false,pause.get("shifting"));
        assertFalse(((String)pause.get("message")).contains("Earlier navigation attempt failed"));
    }

    @Test void partialSuccessAndPlanningWaitRetainTheirOriginalSemantics() throws Exception {
        var partial=runNavigation("partial");
        assertEquals("SUCCESS",partial.get("state"));assertEquals("",partial.get("message"));
        assertEquals(true,partial.get("partial"));assertEquals(3,partial.get("shortfall"));
        var planning=runNavigation("planning");
        assertEquals("RUNNING",planning.get("state"));assertEquals("",planning.get("message"));
        assertEquals(1L,planning.get("progressTick"),"planning tick is still excluded from the stall clock");
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> runNavigation(String scenario) throws Exception {
        return (Map<String,Object>)navigationFixture.getMethod("run",String.class).invoke(null,scenario);
    }

    static void assertWrongTool(Map<String,Object> result) {
        assertEquals(false, result.get("accepted"));
        assertEquals("WRONG_TOOL", result.get("type"));
        assertFalse(((String) result.get("message")).isBlank());
    }

    static void add(Path src, List<String> args, String relative, String source) throws Exception {
        Path path = src.resolve(relative);
        Files.createDirectories(path.getParent());
        Files.writeString(path, source, StandardCharsets.UTF_8);
        args.add(path.toString());
    }

    // These two production methods are at class indentation. Preserve each method
    // byte-for-byte; require a unique signature and its class-level closing brace.
    static String method(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0 && source.indexOf(signature, start + 1) < 0, "unique production method: " + signature);
        int end = source.indexOf("\n    }", start);
        assertTrue(end > start, "complete production method: " + signature);
        return source.substring(start, end + "\n    }".length());
    }
}
