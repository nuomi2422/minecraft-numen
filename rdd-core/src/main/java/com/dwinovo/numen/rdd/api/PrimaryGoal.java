package com.dwinovo.numen.rdd.api;

import java.util.List;

/**
 * 一个一级任务（大阶段）：含一串二级检查项。
 *
 * @param waitFor 前置资产要求：进入本一级前，背包需先持有这些资产
 *                （否则该一级保持 WAITING，不派给 AI）。空 = 无前置依赖，按顺序直接开始。
 */
public record PrimaryGoal(String id, String description, List<Subtask> subtasks, List<AssetRequirement> waitFor) {
    public PrimaryGoal {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("primary goal id required");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("primary goal description required");
        if (subtasks == null || subtasks.isEmpty()) throw new IllegalArgumentException("at least one subtask required");
        subtasks = List.copyOf(subtasks);
        waitFor = waitFor == null || waitFor.isEmpty() ? List.of() : List.copyOf(waitFor);
    }

    /** 无前置依赖的便捷构造。 */
    public PrimaryGoal(String id, String description, List<Subtask> subtasks) {
        this(id, description, subtasks, List.of());
    }
}
