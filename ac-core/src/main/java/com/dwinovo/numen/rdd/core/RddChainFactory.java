package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;

import java.util.Map;
import java.util.UUID;

/**
 * 从一个自然语言目标（{@code /goal X} 的正文）造出最小可跑的 RDD 任务链。
 *
 * <p>本轮是最小闭环：目标正文直接作为一级目标，二级目标用一个 HARD_CODED 占位
 * （asset_key="goal"，minimum=1，表示「目标已受理」）。真正的 LLM 自动分解
 * （objective → 多个带真实检测条件的二级目标）是下一轮的工作，届时替换
 * {@link #fromObjective} 内部即可，调用方不变。
 *
 * <p>纯 JVM，不碰 Minecraft。
 */
public final class RddChainFactory {

    /** 目标正文截断上限，与 {@code GoalState.MAX_OBJECTIVE_CHARS} 对齐。 */
    public static final int MAX_OBJECTIVE_CHARS = 4000;

    private RddChainFactory() {}

    /**
     * 由目标正文构造一个单一级目标的 Goal。
     *
     * @param companionId 同伴 UUID，用于生成稳定的节点 id
     * @param objective   目标正文；空白视为非法
     */
    public static Goal fromObjective(UUID companionId, String objective) {
        if (companionId == null) {
            throw new IllegalArgumentException("companionId required");
        }
        String obj = objective == null ? "" : objective.strip();
        if (obj.isEmpty()) {
            throw new IllegalArgumentException("objective required");
        }
        if (obj.length() > MAX_OBJECTIVE_CHARS) {
            obj = obj.substring(0, MAX_OBJECTIVE_CHARS);
        }
        String suffix = companionId.toString().substring(0, 8);
        String primaryId = "primary-" + suffix;
        String subtaskId = "subtask-" + suffix;
        Subtask subtask = Subtask.hardCoded(
                subtaskId,
                "working toward: " + obj,
                Map.of("asset_key", "goal", "minimum", 1));
        PrimaryGoal primary = new PrimaryGoal(primaryId, obj, java.util.List.of(subtask));
        return new Goal("goal-" + suffix, obj, java.util.List.of(primary));
    }
}
