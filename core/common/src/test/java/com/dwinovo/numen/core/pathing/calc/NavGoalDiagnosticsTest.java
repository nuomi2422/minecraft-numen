package com.dwinovo.numen.core.pathing.calc;

import com.google.gson.JsonParser;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11-12: headless failure-projection regression from live COLUMN calls.
 * context-1794428075808.jsonl:286/304 correlates with tools.jsonl:12419/12424.
 * The original failure is evidence of misleading geometry, not evidence of why no route existed.
 */
class NavGoalDiagnosticsTest {
    private static final String FIRST_ARGS = "{\"x\": 30430, \"y\": null, \"z\": 30160}";
    private static final String SECOND_ARGS = "{\"x\": 30430, \"y\": null, \"z\": 30160, \"may_alter_terrain\": true}";
    private static final String TERRAIN_RESULT = "blocked: stopped 35.3 blocks short horizontally and 64 blocks below you — target is location x=30430 z=30160 (I'm on the ground at y=64). no route without altering terrain (from 30439, 64, 30194 toward 30430, 0, 30160, about 73 blocks away). The cheapest route through would place 3 blocks. Altering terrain needs consent: if that is acceptable, goto there with may_alter_terrain=true; otherwise pick another spot or ask. (also retried accepting anywhere within 3 blocks — no path either).（任务已结束，无需 task_stop；确需中止请先 task_status 确认在跑。）";
    private static final String NO_PATH_RESULT = "blocked: stopped 39.5 blocks short horizontally and 64 blocks below you — target is location x=30430 z=30160 (I'm on the ground at y=64). no path to target (from 30444, 64, 30197 toward 30430, 0, 30160, about 75 blocks away; the search burned its whole budget without finding a route) (also retried accepting anywhere within 3 blocks — no path either). Try a nearer waypoint or scan_blocks for a way through.（任务已结束，无需 task_stop；确需中止请先 task_status 确认在跑。）";

    private static NavGoal liveColumn(String arguments) {
        var a = JsonParser.parseString(arguments).getAsJsonObject();
        assertTrue(a.get("y").isJsonNull(), "schema permits LEAVE NULL surface intent");
        MoveToTaskRecord r = new MoveToTaskRecord("fixture", 0L,
                a.get("x").getAsDouble(), null, a.get("z").getAsDouble(), null,
                a.has("may_alter_terrain") && a.get("may_alter_terrain").getAsBoolean());
        assertEquals(MoveToTaskRecord.Kind.COLUMN, r.kind);
        assertNull(r.y);
        return NavGoal.column((int) Math.floor(r.x), (int) Math.floor(r.z));
    }

    @Test
    void liveTerrainBlockedColumnDoesNotInventDepth() {
        assertTrue(TERRAIN_RESULT.contains("64 blocks below you"));
        assertTrue(TERRAIN_RESULT.contains("toward 30430, 0, 30160, about 73 blocks away"));
        assertTrue(TERRAIN_RESULT.contains("place 3 blocks"));
        assertTrue(TERRAIN_RESULT.contains("Altering terrain needs consent"));
        NavGoal goal = liveColumn(FIRST_ARGS);
        assertEquals("stopped 35.3 blocks short horizontally; target height unspecified (COLUMN)",
                NavGoalDiagnostics.stoppedGeometry(goal, 35.3, 64));
        assertEquals("from 30439, 64, 30194 toward column x=30430 z=30160 (target height unspecified), about 35 blocks horizontally",
                NavGoalDiagnostics.approachGeometry(goal, new BlockPos(30439, 64, 30194)));
    }

    @Test
    void liveNoPathColumnDoesNotInventDepth() {
        assertTrue(NO_PATH_RESULT.contains("no path to target"));
        assertTrue(NO_PATH_RESULT.contains("toward 30430, 0, 30160, about 75 blocks away"));
        assertTrue(NO_PATH_RESULT.contains("also retried accepting anywhere within 3 blocks"));
        NavGoal goal = liveColumn(SECOND_ARGS);
        assertEquals("stopped 39.5 blocks short horizontally; target height unspecified (COLUMN)",
                NavGoalDiagnostics.stoppedGeometry(goal, 39.5, 64));
        assertEquals("from 30444, 64, 30197 toward column x=30430 z=30160 (target height unspecified), about 40 blocks horizontally",
                NavGoalDiagnostics.approachGeometry(goal, new BlockPos(30444, 64, 30197)));
    }

    @Test
    void resolvedColumnReferenceIsLabeledEvenWhenZero() {
        NavGoal goal = NavGoal.columnAt(30430, 30160, 0, 3);
        String reference = "target height unspecified; resolved surface reference y=0 +/-3 (not a user-specified Y)";
        assertEquals("stopped 35.3 blocks short horizontally; " + reference,
                NavGoalDiagnostics.stoppedGeometry(goal, 35.3, 64));
        assertEquals("from 30439, 64, 30194 toward column x=30430 z=30160 (" + reference + "), about 35 blocks horizontally",
                NavGoalDiagnostics.approachGeometry(goal, new BlockPos(30439, 64, 30194)));
    }

    @Test
    void anonymousColumnRetryKeepsHorizontalDiagnostics() {
        NavGoal column = NavGoal.column(30430, 30160);
        NavGoal retry = new NavGoalDiagnostics.ColumnTarget() {
            @Override public boolean isAt(BlockPos feet) {
                double dx = feet.getX() - 30430;
                double dz = feet.getZ() - 30160;
                return dx * dx + dz * dz <= 9;
            }
            @Override public double heuristic(BlockPos from) { return column.heuristic(from); }
            @Override public BlockPos center() { return column.center(); }
        };
        assertEquals("from 30439, 64, 30194 toward column x=30430 z=30160 (target height unspecified), about 35 blocks horizontally",
                NavGoalDiagnostics.approachGeometry(retry, new BlockPos(30439, 64, 30194)));
        assertEquals(new BlockPos(30430, 0, 30160), retry.center());
        assertTrue(retry.isAt(new BlockPos(30433, 200, 30160)));
        assertFalse(retry.isAt(new BlockPos(30434, 64, 30160)));
    }

    @Test
    void explicitZeroHeightRemainsARealBlockTarget() {
        NavGoal goal = NavGoal.exact(new BlockPos(30430, 0, 30160));
        assertEquals("stopped 35.3 blocks short horizontally and 64 blocks below you",
                NavGoalDiagnostics.stoppedGeometry(goal, 35.3, 64));
        assertEquals("from 30439, 64, 30194 toward 30430, 0, 30160, about 73 blocks away",
                NavGoalDiagnostics.approachGeometry(goal, new BlockPos(30439, 64, 30194)));
    }

    @Test
    void blockAboveRetainsItsTrueHeightDifference() {
        assertEquals("stopped 0.0 blocks short horizontally and 24 blocks above you",
                NavGoalDiagnostics.stoppedGeometry(NavGoal.exact(new BlockPos(-878, 14, -908)), 0, -10));
    }

    @Test
    void yLevelUsesOnlyItsRealVerticalAxis() {
        NavGoal goal = NavGoal.yLevel(0);
        assertEquals("stopped at elevation y=64, 64 blocks short",
                NavGoalDiagnostics.stoppedGeometry(goal, 0, 64));
        assertEquals("from 30439, 64, 30194 toward elevation y=0, about 64 blocks vertically",
                NavGoalDiagnostics.approachGeometry(goal, new BlockPos(30439, 64, 30194)));
    }

    @Test
    void projectionDoesNotEvaluateArrivalOrSearch() {
        NavGoal custom = new NavGoal() {
            @Override public boolean isAt(BlockPos p) { throw new AssertionError("arrival must not run"); }
            @Override public double heuristic(BlockPos p) { throw new AssertionError("search must not run"); }
            @Override public BlockPos center() { return new BlockPos(3, 4, 0); }
        };
        assertEquals("from 0, 0, 0 toward 3, 4, 0, about 5 blocks away",
                NavGoalDiagnostics.approachGeometry(custom, BlockPos.ZERO));
    }
    // tools.jsonl:13385 / context.jsonl:278, call_00_u1wc0blnoffrn0ik8qlyd7j4.
    // Static fixture only until the managed exact test channel runs these commits.
    @Test
    void liveElevationOnlyRequestDoesNotSuggestWalkingTowardWorldOrigin() {
        var args = JsonParser.parseString(
                "{\"x\":null,\"y\":76,\"z\":null,\"may_alter_terrain\":true}").getAsJsonObject();
        assertTrue(args.get("x").isJsonNull());
        assertTrue(args.get("z").isJsonNull());
        MoveToTaskRecord record = new MoveToTaskRecord("call_00_u1wc0blnoffrn0ik8qlyd7j4", 0,
                null, args.get("y").getAsDouble(), null, null, true);
        assertEquals(MoveToTaskRecord.Kind.YLEVEL, record.kind);
        NavGoal goal = NavGoal.yLevel((int) Math.floor(record.y));
        assertEquals("stopped at elevation y=52, 24 blocks short",
                NavGoalDiagnostics.stoppedGeometry(goal, 0, 52));
        assertEquals("from 30451, 52, 30260 toward elevation y=76, about 24 blocks vertically",
                NavGoalDiagnostics.approachGeometry(goal, new BlockPos(30451, 52, 30260)));
        assertEquals("The target elevation is y=76, 24 blocks above you."
                        + " Look for stairs or a ladder toward that elevation; no horizontal destination was requested.",
                NavGoalDiagnostics.recoveryAdvice(goal, 30451, 52, 30260));
    }

    @Test
    void elevationAdviceIgnoresHorizontalPositionIncludingNegativeAndZeroCoordinates() {
        for (double[] xz : new double[][]{{30451.75, 30260.25}, {-30451.75, -30260.25}, {0, 0}}) {
            String below = NavGoalDiagnostics.recoveryAdvice(NavGoal.yLevel(0), xz[0], 52, xz[1]);
            assertEquals("The target elevation is y=0, 52 blocks below you."
                    + " Look for stairs or a ladder toward that elevation; no horizontal destination was requested.", below);
            assertFalse(below.contains("First goto"));
            assertFalse(below.contains("too far"));
            assertFalse(below.contains("x="));
            assertFalse(below.contains("z="));
        }
        assertEquals("The requested elevation y=0 matches the current feet height; recheck the current position before retrying.",
                NavGoalDiagnostics.recoveryAdvice(NavGoal.yLevel(0), -30000, 0, 40000));
    }

    @Test
    void realBlockAndColumnRetainSixtyFourBlockRelayInTargetDirection() {
        String expected = "This target is 500 blocks away — too far for one path."
                + " First goto x=-30038 z=-40051 (about 64 blocks toward it; omit y, it resolves to the surface),"
                + " then call goto again for the final target.";
        for (NavGoal goal : new NavGoal[]{
                NavGoal.exact(new BlockPos(-30300, 0, -40400)),
                NavGoal.column(-30300, -40400),
                NavGoal.columnAt(-30300, -40400, 0, 2),
                NavGoal.getToBlock(new BlockPos(-30300, 0, -40400))}) {
            assertEquals(expected, NavGoalDiagnostics.recoveryAdvice(goal, -29999.5, 52, -39999.5));
        }
    }

    @Test
    void realZeroCoordinatesRemainHorizontalTargetsAndFeetPrecisionIsKept() {
        assertEquals("This target is 500 blocks away — too far for one path."
                        + " First goto x=262 z=349 (about 64 blocks toward it; omit y, it resolves to the surface),"
                        + " then call goto again for the final target.",
                NavGoalDiagnostics.recoveryAdvice(NavGoal.column(0, 0), 300.5, 52, 400.5));
        assertEquals("This target is 300 blocks away — too far for one path."
                        + " First goto x=236 z=300 (about 64 blocks toward it; omit y, it resolves to the surface),"
                        + " then call goto again for the final target.",
                NavGoalDiagnostics.recoveryAdvice(NavGoal.exact(new BlockPos(0, 0, 300)), 300.25, 52, 300));
    }

    @Test
    void shortRangeAndThresholdLeaveExistingCallerAdviceInCharge() {
        assertNull(NavGoalDiagnostics.recoveryAdvice(NavGoal.column(30479, 30223), 30470, 10, 30228));
        assertNull(NavGoalDiagnostics.recoveryAdvice(NavGoal.column(128, 0), 0.5, 52, 0.5));
        assertNull(NavGoalDiagnostics.recoveryAdvice(NavGoal.exact(new BlockPos(0, -200, 0)), 0, 200, 0));
        assertTrue(NavGoalDiagnostics.recoveryAdvice(NavGoal.column(129, 0), 0.5, 52, 0.5)
                .contains("First goto x=64 z=0"));
    }

    @Test
    void missingFindTargetStaysUnknownAndUnrecognizedGoalsDoNotInventCoordinates() {
        String unknown = "The target location is unknown; use scan_blocks to locate a target before choosing a waypoint.";
        assertEquals(unknown, NavGoalDiagnostics.recoveryAdvice(null, 30451, 52, 30260));
        assertEquals(unknown, NavGoalDiagnostics.recoveryAdvice(null, 0, 52, 0));
        NavGoal unrecognized = new NavGoal() {
            @Override public boolean isAt(BlockPos p) { throw new AssertionError("arrival must not run"); }
            @Override public double heuristic(BlockPos p) { throw new AssertionError("search must not run"); }
            @Override public BlockPos center() { throw new AssertionError("unknown axes must not be guessed"); }
        };
        assertEquals(unknown, NavGoalDiagnostics.recoveryAdvice(unrecognized, -30000, 52, -40000));
    }

    @Test
    void anonymousColumnAdviceUsesOnlyCenterWithoutEvaluatingNavigation() {
        NavGoal retry = new NavGoalDiagnostics.ColumnTarget() {
            @Override public boolean isAt(BlockPos p) { throw new AssertionError("arrival must not run"); }
            @Override public double heuristic(BlockPos p) { throw new AssertionError("search must not run"); }
            @Override public BlockPos center() { return new BlockPos(-30300, 0, -40400); }
        };
        assertEquals(NavGoalDiagnostics.recoveryAdvice(NavGoal.column(-30300, -40400), -30000, 52, -40000),
                NavGoalDiagnostics.recoveryAdvice(retry, -30000, 52, -40000));
    }

}
