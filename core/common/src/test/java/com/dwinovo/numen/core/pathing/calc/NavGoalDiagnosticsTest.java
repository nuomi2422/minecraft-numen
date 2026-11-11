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
}
