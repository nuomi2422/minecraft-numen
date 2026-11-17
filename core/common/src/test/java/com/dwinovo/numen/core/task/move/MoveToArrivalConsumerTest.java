package com.dwinovo.numen.core.task.move;

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
 * 3238 t5 follow-up: exact production arrival methods, not another GoalXZ formula.
 * Like MineHarvestRecoveryConsumerTest, compile source slices with boundary data only.
 * Run this class with the parent-owned headless test runner. Compiler/fixture failures
 * are SETUP ERROR, not business red; no new helper from a proposed fix is required.
 * onTick stops BEFORE the boat branch: only its real early SUCCESS is exercised.
 * successMessage/resultData are verbatim production members with parent signature shells.
 * Calling them directly does NOT test lifecycle SUCCESS delivery or the nav FAILED/settle/retry
 * branches that choose approximate success. onStart, base lifecycle, navigation, support
 * calculation, collision safety and physics are NOT tested.
 * Feet/pathStart are independently supplied MC-boundary results. A solid/empty/nonempty
 * envelope supplies a KNOWN old-resolver reference, NOT safe headroom or the incident's
 * unknown surface. Ordinary two-air-cell ground is separately characterized as MIN_VALUE.
 * Why not full MC/Mockito: that would bootstrap or recreate unrelated movement here.
 */
class MoveToArrivalConsumerTest {
    @TempDir static Path temp;
    static URLClassLoader loader;
    static Class<?> fixture;

    @BeforeAll static void compileProductionSlices() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("core/common/src/main/java"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("SETUP ERROR: cannot locate candidate source tree");
        Path main = root.resolve("core/common/src/main/java/com/dwinovo/numen/core");
        String task = Files.readString(main.resolve("task/move/MoveToCompanionTask.java"));
        String goal = Files.readString(main.resolve("pathing/calc/NavGoal.java"));
        Path src = temp.resolve("src"), out = temp.resolve("classes");
        Files.createDirectories(out);
        List<String> args = new ArrayList<>(List.of("-encoding", "UTF-8", "-proc:none", "-d", out.toString()));
        add(src, args, "com/dwinovo/numen/task/TaskState.java",
                Files.readString(root.resolve("api/common/src/main/java/com/dwinovo/numen/task/TaskState.java")));
        add(src, args, "com/dwinovo/numen/task/TaskRecord.java", """
                package com.dwinovo.numen.task;
                // Transport shell only; production MoveToTaskRecord decides kind/consent.
                public abstract class TaskRecord {
                    protected TaskRecord(String tool, String call, long deadline) { }
                    public abstract String describe();
                }
                """);
        add(src, args, "com/dwinovo/numen/core/task/move/MoveToTaskRecord.java",
                Files.readString(main.resolve("task/move/MoveToTaskRecord.java")));
        add(src, args, "net/minecraft/core/BlockPos.java", """
                package net.minecraft.core;
                public record BlockPos(int x, int y, int z) {
                    public int getX() { return x; } public int getY() { return y; } public int getZ() { return z; }
                    public BlockPos immutable() { return this; }
                    public BlockPos above() { return new BlockPos(x,y+1,z); }
                    public double distSqr(BlockPos p) { return (double)(x-p.x)*(x-p.x)+(double)(y-p.y)*(y-p.y)+(double)(z-p.z)*(z-p.z); }
                }
                """);
        add(src, args, "com/dwinovo/numen/core/pathing/moves/ActionCosts.java",
                Files.readString(main.resolve("pathing/moves/ActionCosts.java")));
        // Used goal vocabulary verbatim, including its actual membership and heuristic methods.
        StringBuilder goals = new StringBuilder("package com.dwinovo.numen.core.pathing.calc;\n"
                + "import net.minecraft.core.BlockPos;\nimport com.dwinovo.numen.core.pathing.moves.ActionCosts;\n");
        goals.append(between(goal, "public interface NavGoal {", "    // ---- the shared octile"));
        for (String signature : List.of("    static double pointBound(", "    static NavGoal exact(",
                "    static NavGoal column(", "    static NavGoal columnAt(", "    static NavGoal yLevel(",
                "    static NavGoal near(", "    static NavGoal getToBlock(", "    final class Exact ",
                "    final class Column ", "    final class YLevel ", "    final class Near ", "    final class GetToBlock ")) {
            goals.append(member(goal, signature)).append('\n');
        }
        add(src, args, "com/dwinovo/numen/core/pathing/calc/NavGoal.java", goals.append("}\n").toString());
        add(src, args, "com/dwinovo/numen/core/pathing/calc/NavGoalDiagnostics.java", """
                package com.dwinovo.numen.core.pathing.calc;
                public final class NavGoalDiagnostics { public interface ColumnTarget extends NavGoal { } }
                """);
        add(src, args, "com/dwinovo/numen/core/pathing/execute/PlayerNav.java", """
                package com.dwinovo.numen.core.pathing.execute;
                public final class PlayerNav { public enum ContextProvider { DEFAULT, TERRAFORM } }
                """);
        add(src, args, "com/dwinovo/numen/core/pathing/moves/Movement.java", """
                package com.dwinovo.numen.core.pathing.moves;
                import net.minecraft.core.BlockPos;
                import com.dwinovo.numen.core.task.move.MoveToCompanionTask.NumenPlayer;
                public final class Movement { public static BlockPos pathStart(NumenPlayer p) { return p.start; } }
                """);
        add(src, args, "com/dwinovo/numen/core/pathing/util/BlockHelper.java", """
                package com.dwinovo.numen.core.pathing.util;
                import net.minecraft.core.BlockPos;
                import com.dwinovo.numen.core.task.move.MoveToCompanionTask.World;
                public final class BlockHelper {
                    public static BlockPos playerFeet(World w, double x, double y, double z) { return w.feet; }
                }
                """);
        StringBuilder consumer = new StringBuilder("""
                package com.dwinovo.numen.core.task.move;
                import java.util.*;
                import net.minecraft.core.BlockPos;
                import com.dwinovo.numen.task.TaskState;
                import com.dwinovo.numen.core.pathing.calc.*;
                import com.dwinovo.numen.core.pathing.execute.PlayerNav;
                class ArrivalBoundary {
                    final MoveToCompanionTask.NumenPlayer player; final MoveToTaskRecord r;
                    ArrivalBoundary(MoveToCompanionTask.NumenPlayer p, MoveToTaskRecord r) { player=p; this.r=r; }
                    // Signature shells only, so production @Override annotations remain intact.
                    protected String successMessage() { throw new UnsupportedOperationException(); }
                    protected Map<String,Object> resultData() { throw new UnsupportedOperationException(); }
                }
                public final class MoveToCompanionTask extends ArrivalBoundary {
                    private final int bx, by, bz;
                    private final BlockPos blockTarget;
                    private Finder finder;
                    record Contract(NavGoal goal) { }
                    record Finder(Contract contract) {
                        BlockPos nearest() { return contract == null ? null : contract.goal().center(); }
                    }
                    public record Shape(boolean empty) { public boolean isEmpty() { return empty; } }
                    public record State(boolean empty) {
                        public Shape getCollisionShape(World w, BlockPos p) { return new Shape(empty); }
                    }
                    public static class World {
                        public BlockPos feet; String envelope; int groundY=79, scans;
                        public int getMinBuildHeight() { return -64; }
                        public int getMaxBuildHeight() { scans++; return 320; }
                        public State getBlockState(BlockPos p) {
                            boolean target=p.getX()==6185 && p.getZ()==6130;
                            // Collision emptiness only: no claimed safe player AABB.
                            boolean occupied=target && !envelope.equals("empty")
                                && (p.getY()==groundY || (envelope.equals("singleGap") && p.getY()==groundY+2));
                            return new State(!occupied);
                        }
                    }
                    public static class NumenPlayer {
                        public final World world=new World(); public BlockPos start;
                        boolean ground, water; double extraX;
                        // Opt-in body coordinates; existing fixtures keep their exact old defaults.
                        double[] position;
                        public World level() { return world; }
                        public BlockPos blockPosition() { return position == null ? world.feet
                            : new BlockPos((int)Math.floor(position[0]),(int)Math.floor(position[1]),(int)Math.floor(position[2])); }
                        public double getX() { return position == null ? world.feet.getX()+0.5+extraX : position[0]; }
                        public double getY() { return position == null ? world.feet.getY() : position[1]; }
                        public double getZ() { return position == null ? world.feet.getZ()+0.5 : position[2]; }
                        public boolean onGround() { return ground; } public boolean isInWater() { return water; }
                    }
                    static class OutsideEarlySlice extends RuntimeException { }
                    private String earlyState() {
                        try { return onTick().name(); } catch (OutsideEarlySlice outside) { return "OUTSIDE_EARLY_SLICE"; }
                    }
                    public static Map<String,Object> run(String kind, String envelope, int fx, int fy, int fz,
                            int sx, int sy, int sz, boolean ground, boolean water, boolean permit) {
                        NumenPlayer p=new NumenPlayer();
                        p.world.envelope=envelope; p.world.feet=new BlockPos(fx,fy,fz);
                        p.start=new BlockPos(sx,sy,sz); p.ground=ground; p.water=water;
                        MoveToTaskRecord r=switch(kind) {
                            case "COLUMN" -> new MoveToTaskRecord("test",0,6185d,null,6130d,null,permit);
                            case "BLOCK" -> new MoveToTaskRecord("test",0,6185d,80d,6130d,null,permit);
                            case "YLEVEL" -> new MoveToTaskRecord("test",0,null,80d,null,null,permit);
                            case "FIND" -> new MoveToTaskRecord("test",0,null,null,null,"minecraft:chest",permit);
                            default -> throw new IllegalArgumentException(kind);
                        };
                        MoveToCompanionTask t=new MoveToCompanionTask(p,r);
                        if(kind.equals("FIND")) t.finder=new Finder(new Contract(NavGoal.getToBlock(new BlockPos(6185,80,6130))));
                        Map<String,Object> result=new HashMap<>();
                        result.put("kind",r.kind.name()); result.put("permit",r.mayAlterTerrain);
                        result.put("terrain",t.terrain().name());
                        result.put("ref",t.resolveColumnSurfaceY());
                        result.put("searchFeet",t.columnGoal().isAt(p.world.feet));
                        result.put("reached",t.reached()); result.put("earlyState",t.earlyState());
                        result.put("close",t.closeEnoughToSucceed());
                        if(kind.equals("COLUMN") || kind.equals("BLOCK")) result.put("retry",t.nearRetryGoal().isAt(p.world.feet));
                        result.put("legacy",NavGoal.column(6185,6130).isAt(p.world.feet));
                        result.put("message",t.successMessage());
                        return result;
                    }
                    public static Map<String,Object> blockCopy(double x, double y, double z, int feetY,
                            boolean ground, boolean water) {
                        NumenPlayer p=new NumenPlayer(); p.world.envelope="twoAir";
                        // MC-boundary results are supplied, not a replacement feet/support resolver.
                        p.world.feet=new BlockPos((int)Math.floor(x),feetY,(int)Math.floor(z));
                        p.start=p.world.feet; p.position=new double[]{x,y,z}; p.ground=ground; p.water=water;
                        MoveToCompanionTask t=new MoveToCompanionTask(p,
                            new MoveToTaskRecord("copy",0,385d,51d,624d,null,false));
                        // Probe keys below exist only in this test; production resultData is unchanged.
                        return Map.of("close",t.closeEnoughToSucceed(),"exact",t.reached(),
                            "message",t.successMessage(),"data",t.resultData());
                    }
                    public static Map<String,Object> radius(double offset) {
                        NumenPlayer p=new NumenPlayer(); p.world.envelope="singleGap";
                        p.world.feet=new BlockPos(6188,80,6130); p.start=p.world.feet;
                        p.ground=true; p.extraX=offset;
                        MoveToCompanionTask t=new MoveToCompanionTask(p,
                            new MoveToTaskRecord("radius",0,6185d,null,6130d,null,false));
                        return Map.of("close",t.closeEnoughToSucceed(),"retry",t.nearRetryGoal().isAt(p.world.feet));
                    }
                    public static Map<String,Object> membershipScan(int fx, int fz, int sx, int sz) {
                        NumenPlayer p=new NumenPlayer(); p.world.envelope="singleGap";
                        p.world.feet=new BlockPos(6185+fx,80,6130+fz);
                        p.start=new BlockPos(6185+sx,80,6130+sz); p.ground=true;
                        MoveToCompanionTask t=new MoveToCompanionTask(p,
                            new MoveToTaskRecord("scan",0,6185d,null,6130d,null,false));
                        boolean reached=t.reached();
                        return Map.of("reached",reached,"scans",p.world.scans);
                    }
                    public static Map<String,Object> referenceChange() {
                        NumenPlayer p=new NumenPlayer(); p.world.envelope="singleGap";
                        p.world.feet=new BlockPos(6185,80,6130); p.start=p.world.feet; p.ground=true;
                        MoveToCompanionTask t=new MoveToCompanionTask(p,
                            new MoveToTaskRecord("refresh",0,6185d,null,6130d,null,false));
                        int before=p.world.scans;
                        boolean reached=t.reached(); int scans=p.world.scans-before;
                        NavGoal retry=t.nearRetryGoal();
                        p.world.groundY=89;
                        return Map.of("before",reached,"membershipScans",scans,"after",t.reached(),
                            "closeAfter",t.closeEnoughToSucceed(),"frozenRetry",retry.isAt(p.world.feet),
                            "freshRetry",t.nearRetryGoal().isAt(p.world.feet));
                    }
                """);
        for (String name : List.of("COLUMN_Y_TOLERANCE", "COLUMN_SCAN_UP", "COLUMN_SCAN_DOWN",
                "NEAR_SUCCESS_RADIUS", "VERTICAL_SUCCESS_SLACK")) {
            int at = unique(task, " " + name + " =");
            consumer.append(task, task.lastIndexOf('\n', at) + 1, task.indexOf(';', at) + 1).append('\n');
        }
        for (String signature : List.of("    public MoveToCompanionTask(", "    private PlayerNav.ContextProvider terrain()",
                "    private NavGoal columnGoal()", "    private int resolveColumnSurfaceY()",
                "    private boolean reached()", "    private boolean inGoalCell(", "    private BlockPos feet()",
                "    private boolean closeEnoughToSucceed()", "    private NavGoal nearRetryGoal()",
                "    private double horizontalDistSqr(", "    private NavGoal blockGoal()")) {
            consumer.append(member(task, signature)).append('\n');
        }
        // Preserve the real override declarations, using only the parent signature shells above.
        consumer.append(member(task, "    @Override\n    protected String successMessage()")).append('\n');
        consumer.append(member(task, "    @Override\n    protected Map<String, Object> resultData()")).append('\n');
        // Exact standOn statements; engine/sacred plumbing is outside these arrival assertions.
        String compiler = Files.readString(main.resolve("pathing/goal/GoalCompiler.java"));
        add(src, args, "com/dwinovo/numen/core/pathing/goal/GoalCompiler.java", """
                package com.dwinovo.numen.core.pathing.goal;
                import net.minecraft.core.BlockPos;
                import com.dwinovo.numen.core.pathing.calc.NavGoal;
                public final class GoalCompiler {
                    public record Compiled(NavGoal goal, Object sacred) { }
                    static class LongSets { static Object emptySet() { return null; } }
                """ + member(compiler, "    public static Compiled standOn(") + "\n}\n");
        consumer.append(member(task, "    private com.dwinovo.numen.core.pathing.goal.GoalCompiler.Compiled blockCompiled()")).append('\n');
        // Verbatim prefix includes the actual early return. Probe tail is NOT a production state.
        String earlyTick = between(task, "    protected TaskState onTick() {",
                "        if (boatLeg != null) {\n            return tickBoatLeg();");
        if (!earlyTick.stripTrailing().endsWith("if (reached()) return TaskState.SUCCESS;")) {
            throw new IllegalStateException("SETUP ERROR: early-tick slice no longer ends at the isolated SUCCESS return");
        }
        consumer.append(earlyTick).append("        throw new OutsideEarlySlice();\n    }\n}\n");
        add(src, args, "com/dwinovo/numen/core/task/move/MoveToCompanionTask.java", consumer.toString());
        var javac = ToolProvider.getSystemJavaCompiler();
        if (javac == null) throw new IllegalStateException("SETUP ERROR: a JDK compiler is required");
        var diagnostics = new ByteArrayOutputStream();
        if (javac.run(null, diagnostics, diagnostics, args.toArray(String[]::new)) != 0) {
            throw new IllegalStateException("SETUP ERROR: production slices did not compile; NOT a business red:\n"
                    + diagnostics.toString(StandardCharsets.UTF_8));
        }
        loader = new URLClassLoader(new URL[]{out.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        fixture = loader.loadClass("com.dwinovo.numen.core.task.move.MoveToCompanionTask");
    }

    @AfterAll static void close() throws Exception { if (loader != null) loader.close(); }

    @Test void sameColumnOutsideKnownReferenceCannotTakeTheRealEarlySuccess() throws Exception {
        var r = column("singleGap", 0, 61, 0, 61, false, true, false);
        assertEquals(80, r.get("ref"), "controlled resolver reference, NOT the unknown t5 surface");
        assertEquals(false, r.get("searchFeet"));
        var allowed = column("singleGap", 0, 61, 0, 61, false, true, true);
        assertEquals("TERRAFORM", allowed.get("terrain"));
        assertAll("task must not bypass its own known reference (19 > production tolerance 2)",
                () -> assertEquals(false, r.get("reached"), "real reached() ignores resolved Y"),
                () -> assertEquals("OUTSIDE_EARLY_SLICE", r.get("earlyState"), "real onTick early return must not report SUCCESS"),
                () -> assertEquals("OUTSIDE_EARLY_SLICE", allowed.get("earlyState"), "terrain consent must not waive arrival Y"));
    }

    @Test void inclusiveToleranceAndLegacyAnyHeightAreSeparateContracts() throws Exception {
        for (int y : List.of(78, 80, 82)) {
            var r = column("singleGap", 0, y, 0, y, true, false, false);
            assertEquals(true, r.get("searchFeet"));
            assertEquals(true, r.get("reached")); assertEquals("SUCCESS", r.get("earlyState"));
        }
        var below = column("singleGap", 0, 77, 0, 77, true, false, false);
        var above = column("singleGap", 0, 83, 0, 83, true, false, false);
        for (var r : List.of(below, above)) {
            assertEquals(false, r.get("searchFeet"));
            assertEquals(true, r.get("legacy"), "two-argument NavGoal.column must KEEP any-height semantics");
        }
        assertAll("one beyond both inclusive tolerance edges",
                () -> assertEquals(false, below.get("reached"), "y=77"),
                () -> assertEquals(false, above.get("reached"), "y=83"));
    }

    @Test void missingReferenceKeepsFallbackIncludingOrdinaryTwoAirCellGround() throws Exception {
        for (String world : List.of("empty", "twoAir")) {
            for (int y : List.of(61, 120)) {
                var r = column(world, 0, y, 0, y, true, false, false);
                assertEquals(Integer.MIN_VALUE, r.get("ref"));
                assertEquals(true, r.get("searchFeet")); assertEquals(true, r.get("reached"));
                assertEquals("SUCCESS", r.get("earlyState"));
            }
        }
        assertEquals("SUCCESS", column("twoAir", 0, 80, 0, 80, true, false, false).get("earlyState"),
                "ordinary valid standing boundary: solid y79, feet y80 and head y81 empty");
        // Old resolver requires NONEMPTY above.above(): characterization, NOT safe headroom.
        assertEquals(80, column("singleGap", 0, 61, 0, 61, false, false, false).get("ref"));
    }

    @Test void feetAndSupportedPathStartMustBothSatisfyTheColumn() throws Exception {
        for (var r : List.of(column("singleGap", 0, 80, 1, 80, true, false, false),
                column("singleGap", 1, 80, 0, 80, true, false, false))) {
            assertEquals(false, r.get("reached")); assertEquals("OUTSIDE_EARLY_SLICE", r.get("earlyState"));
        }
        var wrongSupportedY = column("singleGap", 0, 80, 0, 61, true, false, false);
        assertEquals(true, wrongSupportedY.get("searchFeet"));
        assertEquals(false, wrongSupportedY.get("reached"), "pathStart Y also needs membership, not only feet Y");
    }

    @Test void approximateSuccessAndRetryMustNotReopenTheVerticalBypass() throws Exception {
        for (int y : List.of(78, 80, 82)) {
            var edge = column("singleGap", 3, y, 3, y, true, false, false);
            assertEquals(true, edge.get("close")); assertEquals(true, edge.get("retry"));
        }
        var outside = column("singleGap", 4, 80, 4, 80, true, false, false);
        assertEquals(false, outside.get("close")); assertEquals(false, outside.get("retry"));
        var low = column("singleGap", 2, 61, 2, 61, true, false, false);
        assertEquals(80, low.get("ref")); assertEquals(false, low.get("reached"));
        assertAll("production closeEnough and actual retry goal must retain the known vertical band",
                () -> assertEquals(false, low.get("close")), () -> assertEquals(false, low.get("retry")));
    }

    @Test void fallbackNearRadiusAndGroundOrWaterGateStayUnchanged() throws Exception {
        var fallback = column("empty", 2, 61, 2, 61, true, false, false);
        assertEquals(true, fallback.get("close")); assertEquals(true, fallback.get("retry"));
        assertEquals(false, column("empty", 4, 61, 4, 61, true, false, false).get("close"));
        assertEquals(false, column("singleGap", 2, 80, 2, 80, false, false, false).get("close"));
        assertEquals(true, column("singleGap", 2, 80, 2, 80, false, true, false).get("close"));
    }

    @Test void otherIntentsAndTerrainSelectionKeepTheirProductionArrivalRules() throws Exception {
        var exact = run("BLOCK", "twoAir", 0, 80, 0, 80, true, false, false);
        assertEquals("BLOCK", exact.get("kind")); assertEquals("SUCCESS", exact.get("earlyState"));
        var below = run("BLOCK", "twoAir", 0, 61, 0, 61, true, false, false);
        assertEquals(false, below.get("reached")); assertEquals(false, below.get("close")); assertEquals(false, below.get("retry"));
        var level = run("YLEVEL", "twoAir", 20, 80, 20, 80, true, false, false);
        assertEquals("YLEVEL", level.get("kind")); assertEquals("SUCCESS", level.get("earlyState"));
        assertEquals(false, run("YLEVEL", "twoAir", 20, 80, 20, 80, false, true, false).get("reached"));
        var find = run("FIND", "twoAir", 1, 80, 1, 80, true, false, false);
        assertEquals("FIND", find.get("kind")); assertEquals("SUCCESS", find.get("earlyState")); assertEquals(false, find.get("close"));
        assertEquals(false, run("FIND", "twoAir", 1, 80, 2, 80, true, false, false).get("reached"));
        for (boolean permit : List.of(false, true)) {
            var r = column("empty", 0, 61, 0, 61, true, false, permit);
            assertEquals(permit, r.get("permit")); assertEquals(permit ? "TERRAFORM" : "DEFAULT", r.get("terrain"));
            assertEquals("SUCCESS", r.get("earlyState"), "consent does not change arrival intent");
        }
    }

    @Test void nearSuccessRetainsContinuousBodyRadiusInsteadOfNodeRadius() throws Exception {
        assertEquals(true, probe("radius", new Class<?>[]{double.class}, -0.1).get("close"));
        assertEquals(true, probe("radius", new Class<?>[]{double.class}, 0.0).get("close"));
        var outside = probe("radius", new Class<?>[]{double.class}, 0.1);
        assertEquals(false, outside.get("close"), "body is 3.1 blocks away although its feet node is only 3 away");
        assertEquals(true, outside.get("retry"), "retry keeps the original BlockPos radius");
    }

    @Test void liveMembershipRefreshesWithoutCachingButOneRetryGoalKeepsItsSnapshot() throws Exception {
        var r = probe("referenceChange", new Class<?>[0]);
        assertEquals(true, r.get("before"));
        assertEquals(1, r.get("membershipScans"), "one reference scan shared by feet AND pathStart");
        assertEquals(false, r.get("after"), "changed resolver reference must not stay cached");
        assertEquals(false, r.get("closeAfter"));
        assertEquals(true, r.get("frozenRetry"), "an already-built retry goal retains its creation reference");
        assertEquals(false, r.get("freshRetry"), "a new retry goal reads the changed reference");
    }

    @Test void columnMembershipRejectsEitherXzMismatchWithoutResolvingSurface() throws Exception {
        Class<?>[] axes = {int.class, int.class, int.class, int.class};
        for (int[] cells : new int[][]{{100,0,0,0}, {0,100,0,0}, {0,0,1,0}, {0,0,0,1}}) {
            var r = probe("membershipScan", axes, cells[0], cells[1], cells[2], cells[3]);
            assertEquals(false, r.get("reached"));
            assertEquals(0, r.get("scans"), "a feet or pathStart XZ mismatch needs no reference scan");
        }
        var sameColumn = probe("membershipScan", axes, 0, 0, 0, 0);
        assertEquals(true, sameColumn.get("reached"));
        assertEquals(1, sameColumn.get("scans"), "same-column feet and pathStart still share one reference");
    }

    @Test void v04NearSuccessReportsActualPositionWithoutInventingVerticalFailure() throws Exception {
        var r = blockCopy(384.4245480558926, 51, 626.3000000119209, 51, true, false);
        assertEquals(true, r.get("close")); assertEquals(false, r.get("exact"));
        String message = (String) r.get("message");
        assertEquals("accepted near the requested cell 385,51,624 (not the exact cell); actual position "
                + "x=384.4245480558926, y=51.0, z=626.3000000119209; horizontal distance 2.10 blocks; "
                + "same slab-aware feet elevation as target cell Y (feet vertical offset 0 blocks).", message);
        for (String forbidden : List.of("shaft", "air", "BELOW", "did NOT reach", "HORIZONTAL")) {
            assertFalse(message.contains(forbidden), forbidden);
        }
        assertEquals(Map.of("final_x", 384.4245480558926, "final_y", 51.0,
                "final_z", 626.3000000119209, "ground_y", 51), r.get("data"));
    }

    @Test void exactBlockKeepsOriginalSuccessCopy() throws Exception {
        var r = blockCopy(385.5, 51, 624.5, 51, true, false);
        assertEquals(true, r.get("exact")); assertEquals(true, r.get("close"));
        assertEquals("reached the exact cell 385,51,624.", r.get("message"));
    }

    @Test void sameElevationWithLateralOffsetDoesNotClaimHorizontalArrival() throws Exception {
        var r = blockCopy(386.5, 51, 624.5, 51, true, false);
        assertEquals(true, r.get("close")); assertEquals(false, r.get("exact"));
        assertEquals("accepted near the requested cell 385,51,624 (not the exact cell); actual position "
                + "x=386.5, y=51.0, z=624.5; horizontal distance 1.00 blocks; "
                + "same slab-aware feet elevation as target cell Y (feet vertical offset 0 blocks).", r.get("message"));
    }

    @Test void inclusiveVerticalSlackReportsSignedFeetOffsets() throws Exception {
        // Deliberately split slab-aware feet, raw blockPosition and continuous body Y.
        // These are boundary inputs only, NOT a simulation of slab/physics resolution.
        var above = blockCopy(386.5, 52.5, 624.5, 53, true, false);
        var below = blockCopy(386.5, 48.5, 624.5, 49, true, false);
        for (var r : List.of(above, below)) {
            assertEquals(true, r.get("close")); assertEquals(false, r.get("exact"));
        }
        assertTrue(((String) above.get("message")).endsWith("slab-aware feet vertical offset +2 blocks from target cell Y."));
        assertTrue(((String) below.get("message")).endsWith("slab-aware feet vertical offset -2 blocks from target cell Y."));
        assertTrue(((String) above.get("message")).contains("y=52.5"));
        assertTrue(((String) below.get("message")).contains("y=48.5"));
        var sameFeetY = blockCopy(386.5, 50.5, 624.5, 51, true, false);
        assertEquals(true, sameFeetY.get("close"));
        assertTrue(((String) sameFeetY.get("message")).endsWith("same slab-aware feet elevation as target cell Y (feet vertical offset 0 blocks)."));
    }

    @Test void oneBeyondEitherVerticalSlackEdgeStillRejectsNearSuccess() throws Exception {
        for (int feetY : List.of(48, 54)) {
            var r = blockCopy(386.5, feetY, 624.5, feetY, true, false);
            assertEquals(false, r.get("close")); assertEquals(false, r.get("exact"));
        }
        // A direct copy probe is NOT evidence of SUCCESS when close/reached are both false.
    }

    @Test void blockNearSuccessKeepsGroundOrWaterGateNotAir() throws Exception {
        assertEquals(true, blockCopy(386.5, 51, 624.5, 51, true, false).get("close"));
        assertEquals(true, blockCopy(386.5, 51, 624.5, 51, false, true).get("close"));
        assertEquals(false, blockCopy(386.5, 51, 624.5, 51, false, false).get("close"));
    }

    @Test void blockNearSuccessUsesContinuousRadiusAtThreeAndThreePointOne() throws Exception {
        var edge = blockCopy(388.5, 51, 624.5, 51, true, false);
        var outside = blockCopy(388.6, 51, 624.5, 51, true, false);
        assertEquals(true, edge.get("close")); assertEquals(false, edge.get("exact"));
        assertEquals(false, outside.get("close")); assertEquals(false, outside.get("exact"));
        assertTrue(((String) edge.get("message")).contains("horizontal distance 3.00 blocks"));
    }

    @Test void columnLevelAndFindKeepOriginalSuccessCopy() throws Exception {
        assertEquals("reached the location x=6185 z=6130 (standing on the ground at y=80).",
                column("singleGap", 0, 80, 0, 80, true, false, false).get("message"));
        assertEquals("reached elevation y=80.",
                run("YLEVEL", "twoAir", 20, 80, 20, 80, true, false, false).get("message"));
        assertEquals("reached elevation y=79 (requested y=80).",
                run("YLEVEL", "twoAir", 20, 79, 20, 79, true, false, false).get("message"));
        assertEquals("arrived beside minecraft:chest at 6185,80,6130 — within reach to use.",
                run("FIND", "twoAir", 1, 80, 1, 80, true, false, false).get("message"));
    }

    static Map<String,Object> blockCopy(double x, double y, double z, int feetY,
                                         boolean ground, boolean water) throws Exception {
        return probe("blockCopy", new Class<?>[]{double.class, double.class, double.class,
                int.class, boolean.class, boolean.class}, x, y, z, feetY, ground, water);
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> probe(String name, Class<?>[] types, Object... args) throws Exception {
        return (Map<String,Object>) fixture.getMethod(name, types).invoke(null, args);
    }

    static Map<String,Object> column(String world, int fx, int fy, int sx, int sy,
                                     boolean ground, boolean water, boolean permit) throws Exception {
        return run("COLUMN", world, fx, fy, sx, sy, ground, water, permit);
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> run(String kind, String world, int fx, int fy, int sx, int sy,
                                  boolean ground, boolean water, boolean permit) throws Exception {
        return (Map<String,Object>) fixture.getMethod("run", String.class, String.class,
                int.class, int.class, int.class, int.class, int.class, int.class,
                boolean.class, boolean.class, boolean.class).invoke(null,
                kind, world, 6185 + fx, fy, 6130, 6185 + sx, sy, 6130, ground, water, permit);
    }

    static void add(Path src, List<String> args, String relative, String source) throws Exception {
        Path path = src.resolve(relative); Files.createDirectories(path.getParent());
        Files.writeString(path, source, StandardCharsets.UTF_8); args.add(path.toString());
    }

    // Existing mine-consumer class-indentation extraction: no rewritten algorithm.
    static String member(String source, String signature) {
        int start = unique(source, signature), end = source.indexOf("\n    }", start);
        if (end < 0) throw new IllegalStateException("SETUP ERROR: incomplete production member " + signature);
        return source.substring(start, end + "\n    }".length());
    }

    static String between(String source, String start, String end) {
        int a = unique(source, start), b = unique(source, end);
        if (b <= a) throw new IllegalStateException("SETUP ERROR: invalid production slice " + start);
        return source.substring(a, b);
    }

    static int unique(String source, String token) {
        int at = source.indexOf(token);
        if (at < 0 || source.indexOf(token, at + 1) >= 0) throw new IllegalStateException("SETUP ERROR: unique source marker " + token);
        return at;
    }
}
