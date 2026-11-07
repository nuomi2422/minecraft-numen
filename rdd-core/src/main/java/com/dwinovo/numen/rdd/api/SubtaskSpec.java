package com.dwinovo.numen.rdd.api;

import java.util.Map;

/**
 * 分解器产出的单个二级目标规格（纯 JVM，可单测）。
 *
 * <p>本片只产 HARD_CODED 二级：{@code condition} 必须能被确定性检测（asset_key + minimum），
 * 由 {@code RddChainFactory.fromSpec} 做完整校验。{@code body} 可空——空表示「只检测、不驱动身体」。
 */
public record SubtaskSpec(String description, Map<String, Object> condition, BodyInstruction body,
                          NodeType nodeType, RecurrenceSpec recurrence) {
    /** 旧构造：默认 MAIN、无周期（旧 JSON/旧调用语义不变）。 */
    public SubtaskSpec(String description, Map<String, Object> condition, BodyInstruction body) {
        this(description, condition, body, NodeType.MAIN, null);
    }
    public SubtaskSpec {
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("spec description required");
        }
        if (condition == null || condition.isEmpty()) {
            throw new IllegalArgumentException("spec condition required");
        }
        condition = Map.copyOf(condition);
        nodeType = nodeType == null ? NodeType.MAIN : nodeType;
    }
}
