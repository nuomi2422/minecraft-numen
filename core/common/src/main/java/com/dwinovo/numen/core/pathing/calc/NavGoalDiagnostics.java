package com.dwinovo.numen.core.pathing.calc;

import net.minecraft.core.BlockPos;

/**
 * Read-only failure-message geometry, shared by goto and PlayerNav.
 * 2026-11-12: tools.jsonl:12419/12424 used COLUMN's representative y=0 as a real
 * target height. Keep this projection separate from arrival, search and movement.
 * No world access or goal evaluation: center() is read only to describe coordinates.
 */
public final class NavGoalDiagnostics {
    private NavGoalDiagnostics() { }

    /** Diagnostic axes for goto's anonymous near-column retry; no navigation methods added. */
    public interface ColumnTarget extends NavGoal { }

    /** Geometry in goto's blocked message; horizontal distance retains the caller's feet precision. */
    public static String stoppedGeometry(NavGoal goal, double horizontal, int currentY) {
        if (isColumn(goal)) {
            return String.format("stopped %.1f blocks short horizontally; %s", horizontal,
                    columnHeight(goal) + (goal instanceof NavGoal.Column g && g.verticalConstrained()
                            ? "" : " (COLUMN)"));
        }
        if (goal instanceof NavGoal.YLevel g) {
            return String.format("stopped at elevation y=%d, %d blocks short", currentY,
                    Math.abs(currentY - g.level));
        }
        int dy = goal.center().getY() - currentY;
        return String.format("stopped %.1f blocks short horizontally and %d blocks %s you",
                horizontal, Math.abs(dy), dy > 0 ? "above" : "below");
    }

    /** Shared geometry for both NO_PATH and TERRAIN_BLOCKED; no failure/advice policy here. */
    public static String approachGeometry(NavGoal goal, BlockPos feet) {
        BlockPos center = goal.center();
        if (isColumn(goal)) {
            double horizontal = Math.hypot((double) center.getX() - feet.getX(),
                    (double) center.getZ() - feet.getZ());
            return String.format("from %s toward column x=%d z=%d (%s), about %.0f blocks horizontally",
                    feet.toShortString(), center.getX(), center.getZ(), columnHeight(goal), horizontal);
        }
        if (goal instanceof NavGoal.YLevel g) {
            return String.format("from %s toward elevation y=%d, about %.0f blocks vertically",
                    feet.toShortString(), g.level, Math.abs((double) g.level - feet.getY()));
        }
        return String.format("from %s toward %s, about %.0f blocks away",
                feet.toShortString(), center.toShortString(), Math.sqrt(feet.distSqr(center)));
    }

    private static boolean isColumn(NavGoal goal) {
        return goal instanceof NavGoal.Column || goal instanceof ColumnTarget;
    }

    private static String columnHeight(NavGoal goal) {
        if (goal instanceof NavGoal.Column g && g.verticalConstrained()) {
            return "target height unspecified; resolved surface reference y=" + g.refY
                    + " +/-" + g.tolerance + " (not a user-specified Y)";
        }
        return "target height unspecified";
    }
}
