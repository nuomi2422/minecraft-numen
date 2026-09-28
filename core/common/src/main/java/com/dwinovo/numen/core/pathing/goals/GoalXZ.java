package com.dwinovo.numen.core.pathing.goals;

import com.dwinovo.numen.core.pathing.settings.NavSettings;

/**
 * XZ 目标:默认任意高度,水平坐标相等即到达。
 *
 * <p><b>垂直容差（2026-09-28 修）</b>：{@code goto} 只给 x+z 时按契约是「走到那个地点，
 * Y 解析到地表」。但旧实现连到达判定都不看 Y —— 实测在丛林树冠 y=62 时，
 * 对 y=47 的地面目标直接判「已到达」（ARRIVED-IN-PLACE feet=425,62,-305 goal-center=424,47,-301），
 * 于是它「到了」又立刻被派新目标，表现成<strong>原地左右横跳、任务永不完成</strong>。
 *
 * <p>现在 COLUMN 目标会把解析出的地表高度传进来（{@link #GoalXZ(int,int,int,int)}），
 * 到达要求同时满足水平相等与垂直落在 ±{@code tolerance} 内；
 * 仍需「任意高度」的调用方（导航内部列追踪等）继续用两参构造，行为逐字不变。
 */
public class GoalXZ implements Goal {

    private static final double SQRT_2 = Math.sqrt(2);

    public final int x;
    public final int z;
    /** 地表参考高度；{@link Integer#MIN_VALUE} = 不约束（保持旧的任意高度语义）。 */
    public final int refY;
    /** 允许的垂直偏差（格）。 */
    public final int tolerance;

    public GoalXZ(int x, int z) {
        this(x, z, Integer.MIN_VALUE, 0);
    }

    public GoalXZ(int x, int z, int refY, int tolerance) {
        this.x = x;
        this.z = z;
        this.refY = refY;
        this.tolerance = Math.max(0, tolerance);
    }

    @Override
    public boolean isInGoal(int x, int y, int z) {
        if (x != this.x || z != this.z) {
            return false;
        }
        if (refY == Integer.MIN_VALUE) {
            return true;   // 旧语义：任意高度
        }
        return Math.abs(y - refY) <= tolerance;
    }

    /** 垂直是否已被约束（供测试与调试用）。 */
    public boolean verticalConstrained() {
        return refY != Integer.MIN_VALUE;
    }

    @Override
    public double heuristic(int x, int y, int z) {
        double xDiff = x - this.x;
        double zDiff = z - this.z;
        return calculate(xDiff, zDiff);
    }

    /**
     * 八方向(octile)距离 × costHeuristic:对角段走 √2,剩余直行,
     * 再乘"全程可疾跑"的乐观单格成本(3.563)。
     */
    public static double calculate(double xDiff, double zDiff) {
        double x = Math.abs(xDiff);
        double z = Math.abs(zDiff);
        double straight;
        double diagonal;
        if (x < z) {
            straight = z - x;
            diagonal = x;
        } else {
            straight = x - z;
            diagonal = z;
        }
        diagonal *= SQRT_2;
        return (diagonal + straight) * NavSettings.get().costHeuristic;
    }

    @Override
    public String toString() {
        return verticalConstrained()
                ? String.format("GoalXZ{x=%d,z=%d,refY=%d,tol=%d}", x, z, refY, tolerance)
                : String.format("GoalXZ{x=%d,z=%d}", x, z);
    }
}
