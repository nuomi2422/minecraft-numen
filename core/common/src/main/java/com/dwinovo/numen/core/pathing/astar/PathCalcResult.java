package com.dwinovo.numen.core.pathing.astar;

import java.util.Optional;

/** 一次路径计算的结论:结果类型 + 可空的路径。 */
public final class PathCalcResult {

    public enum Type {
        /** 找到直达目标的完整路径。 */
        SUCCESS_TO_GOAL,
        /** 找到朝目标推进的部分路径段。 */
        SUCCESS_SEGMENT,
        /** 计算被协作取消。 */
        CANCELLATION,
        /** 搜索烧尽预算仍无可用路径。 */
        FAILURE,
        /** 计算过程抛出异常。 */
        EXCEPTION
    }

    /**
     * 搜索为什么在没找到路时停下。只有 {@link Type#FAILURE} 的结论才携带真实原因；
     * 成功/取消/异常一律 {@link #UNKNOWN}，别让“上一次的终止原因”漂到别的结论上。
     * 由 A* 在真实循环出口判定（nodeCap/timeout/chunkBorder/exhausted），
     * 不是从日志文案反推。
     */
    public enum StopReason {
        /** 没拿到真实终止原因（取消/异常/旧路径/包裹层）——如实未知，不冒充预算耗尽。 */
        UNKNOWN,
        /** 撞到时间预算（primaryTimeout/failureTimeout）。 */
        TIMEOUT,
        /** 撞到单次搜索节点硬上限（maxNodesPerSearch）。 */
        NODE_CAP,
        /** 在未加载 chunk 边界停住（pathingMaxChunkBorderFetch）。 */
        CHUNK_BORDER,
        /** openSet 真正抽空：在允许的动作集合下没有可达路。 */
        EXHAUSTED;

        /** 给模型看的人话。固定文案不再冒充原因。 */
        public String describe() {
            return switch (this) {
                case TIMEOUT -> "the search hit its time budget before finding a route (timeout; not proof the route is impossible)";
                case NODE_CAP -> "the search hit its node cap before finding a route (nodeCap)";
                case CHUNK_BORDER -> "the search stopped at unloaded chunk borders (chunkBorder)";
                case EXHAUSTED -> "the search space was exhausted: no route under the allowed moves (exhausted)";
                case UNKNOWN -> "the search did not report why it stopped (unknown — not proof the route is impossible)";
            };
        }
    }

    private final Type type;
    private final NavPath path;
    private final StopReason stopReason;

    public PathCalcResult(Type type) {
        this(type, null, StopReason.UNKNOWN);
    }

    public PathCalcResult(Type type, NavPath path) {
        this(type, path, StopReason.UNKNOWN);
    }

    public PathCalcResult(Type type, NavPath path, StopReason stopReason) {
        this.type = type;
        this.path = path;
        this.stopReason = stopReason == null ? StopReason.UNKNOWN : stopReason;
        boolean success = type == Type.SUCCESS_TO_GOAL || type == Type.SUCCESS_SEGMENT;
        if (success && path == null) {
            throw new IllegalArgumentException("成功结果必须带路径");
        }
        if (!success && path != null) {
            throw new IllegalArgumentException("非成功结果不得带路径");
        }
        if (type != Type.FAILURE && this.stopReason != StopReason.UNKNOWN) {
            throw new IllegalArgumentException("非 FAILURE 不得携带真实终止原因");
        }
    }

    public Type getType() {
        return type;
    }

    public Optional<NavPath> getPath() {
        return Optional.ofNullable(path);
    }

    /** 搜索真实终止原因；只有 FAILURE 可能非 UNKNOWN。 */
    public StopReason getStopReason() {
        return stopReason;
    }
}
