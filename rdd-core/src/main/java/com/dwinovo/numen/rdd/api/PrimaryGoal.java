package com.dwinovo.numen.rdd.api;

import com.dwinovo.numen.rdd.core.WorldFactConditions;

import java.util.List;
import java.util.Map;

/**
 * 一个一级任务（大阶段）：含一串二级检查项（spec §4.2：一级拥有"懒加载的二级目标集合"）。
 *
 * @param waitFor 前置资产要求：进入本一级前，背包需先持有这些资产
 *                （否则该一级保持 WAITING，不派给 AI）。空 = 无前置依赖，按顺序直接开始。
 * @param unexpanded 是否为"未展开"一级：为 true 时暂不带二级（subtasks 必须为空），
 *                等该一级首次被进入时才懒生成二级（spec §9 懒加载契约：进入前必须生成
 *                至少一个合法可执行的二级）。二级注入后应重建为 unexpanded=false。
 * @param equipmentTarget optional canonical four-slot equipment obligation; null preserves legacy semantics
 */
public record PrimaryGoal(String id, String description, List<Subtask> subtasks,
                          List<AssetRequirement> waitFor, boolean unexpanded,
                          Map<String, String> equipmentTarget) {
    public PrimaryGoal {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("primary goal id required");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("primary goal description required");
        if (unexpanded) {
            if (subtasks != null && !subtasks.isEmpty()) throw new IllegalArgumentException("unexpanded primary cannot carry subtasks");
            subtasks = List.of();
        } else {
            if (subtasks == null || subtasks.isEmpty()) throw new IllegalArgumentException("at least one subtask required unless unexpanded");
            subtasks = List.copyOf(subtasks);
        }
        waitFor = waitFor == null || waitFor.isEmpty() ? List.of() : List.copyOf(waitFor);
        if (equipmentTarget != null) {
            if (!WorldFactConditions.valid(Map.of("type", "equipment", "slots", equipmentTarget))) {
                throw new IllegalArgumentException("invalid equipmentTarget");
            }
            equipmentTarget = Map.copyOf(equipmentTarget);
        }
    }

    /** Legacy construction carries no typed equipment obligation. */
    public PrimaryGoal(String id, String description, List<Subtask> subtasks,
                       List<AssetRequirement> waitFor, boolean unexpanded) {
        this(id, description, subtasks, waitFor, unexpanded, null);
    }

    /** 已展开：无前置依赖的便捷构造。 */
    public PrimaryGoal(String id, String description, List<Subtask> subtasks) {
        this(id, description, subtasks, List.of(), false);
    }

    /** 已展开：带前置依赖的便捷构造。 */
    public PrimaryGoal(String id, String description, List<Subtask> subtasks, List<AssetRequirement> waitFor) {
        this(id, description, subtasks, waitFor, false);
    }

    /** 未展开一级（阶段占位，二级待懒生成）：仅主题描述 + 可选前置。 */
    public static PrimaryGoal unexpanded(String id, String description, List<AssetRequirement> waitFor) {
        return unexpanded(id, description, waitFor, null);
    }

    public static PrimaryGoal unexpanded(String id, String description, List<AssetRequirement> waitFor,
                                         Map<String, String> equipmentTarget) {
        return new PrimaryGoal(id, description, List.of(), waitFor, true, equipmentTarget);
    }
}
