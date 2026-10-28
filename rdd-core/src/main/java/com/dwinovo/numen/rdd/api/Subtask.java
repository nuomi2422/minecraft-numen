package com.dwinovo.numen.rdd.api;

import java.util.Map;

public record Subtask(
        String id,
        String description,
        DetectionMode detectionMode,
        Map<String, Object> condition,
        long detectionIntervalSeconds,
        int maxAiChecks,
        boolean recheckOnUnchanged,
        BodyInstruction body) {
    public Subtask {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("subtask id required");
        if (description == null || description.isBlank()) throw new IllegalArgumentException("subtask description required");
        if (detectionMode == null) throw new IllegalArgumentException("detection mode required");
        condition = condition == null ? Map.of() : Map.copyOf(condition);
        if (detectionMode == DetectionMode.HARD_CODED && condition.isEmpty()) {
            throw new IllegalArgumentException("hard-coded subtask condition required");
        }
        if (detectionMode == DetectionMode.AI_ASSISTED && (detectionIntervalSeconds <= 0 || maxAiChecks <= 0)) {
            throw new IllegalArgumentException("AI-assisted subtask interval and max checks must be positive");
        }
    }

    /** 无身体指令的便捷构造（body = null）。 */
    public Subtask(String id, String description, DetectionMode detectionMode, Map<String, Object> condition,
                   long detectionIntervalSeconds, int maxAiChecks, boolean recheckOnUnchanged) {
        this(id, description, detectionMode, condition, detectionIntervalSeconds, maxAiChecks, recheckOnUnchanged, null);
    }

    public static Subtask hardCoded(String id, String description, Map<String, Object> condition) {
        return new Subtask(id, description, DetectionMode.HARD_CODED, condition, 0, 0, false, null);
    }

    /** 带身体执行指令的硬编码二级目标（如收集 N 个某物品）。 */
    public static Subtask hardCoded(String id, String description, Map<String, Object> condition,
                                    BodyInstruction body) {
        return new Subtask(id, description, DetectionMode.HARD_CODED, condition, 0, 0, false, body);
    }

    public static Subtask aiAssisted(String id, String description, long intervalSeconds, int maxChecks,
                                     boolean recheckOnUnchanged) {
        return new Subtask(id, description, DetectionMode.AI_ASSISTED, Map.of(), intervalSeconds, maxChecks,
                recheckOnUnchanged, null);
    }

    /**
     * 只换判据、其余字段原样（2026-10-28，现场上调用）。
     *
     * <p><b>为什么要这个口</b>：{@code TaskChain.raiseCurrentMinimum} 需要重建一条 Subtask 来改
     * {@code condition.minimum}。若调用方自己逐个字段读出来再拼，就会在**主链路**里出现
     * {@code .detectionIntervalSeconds()} 之类的读取点 —— 而
     * {@code SubtaskModeContractTest.aiOnlyFieldsHaveNoProductionReaderYet} 的契约是
     * 「这些 AI-only 字段在被真正接线前，主链路不许读」。放进 record 自己内部，
     * 读的是本记录的字段、不是新接线，契约与实现都不破。
     */
    public Subtask withCondition(Map<String, Object> nextCondition) {
        return new Subtask(id, description, detectionMode, nextCondition,
                detectionIntervalSeconds, maxAiChecks, recheckOnUnchanged, body);
    }
}
