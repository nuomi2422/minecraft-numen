package com.dwinovo.numen.core.pathing.calc;

import net.minecraft.core.BlockPos;

/**
 * Read-only failure-message geometry and recovery advice for goto and PlayerNav.
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

    /**
     * Coordinate recovery suggestion for blocked and stalled goto; null leaves the
     * caller's existing short-range/material advice in charge. Uses supplied feet
     * coordinates only: diagnostics must not scan terrain or evaluate a goal.
     */
    public static String recoveryAdvice(NavGoal goal, double feetX, int feetY, double feetZ) {
        BlockPos target = goal == null ? BlockPos.ZERO : goal.center();
        // Match goto's existing cell-center horizontalDistSqr, including its 128-block boundary.
        double dx = target.getX() + 0.5 - feetX;
        double dz = target.getZ() + 0.5 - feetZ;
        double h = Math.sqrt(dx * dx + dz * dz);
        if (h <= 128) return null;
        double step = Math.min(64, h);
        double wx = feetX + (target.getX() - feetX) * (step / h);
        double wz = feetZ + (target.getZ() - feetZ) * (step / h);
        return String.format("This target is %.0f blocks away — too far for one path."
                + " First goto x=%d z=%d (about %.0f blocks toward it; omit y, it resolves to the surface),"
                + " then call goto again for the final target.",
                h, (int) Math.round(wx), (int) Math.round(wz), step);
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
