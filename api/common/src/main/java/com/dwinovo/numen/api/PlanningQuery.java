package com.dwinovo.numen.api;

import java.util.List;
import java.util.UUID;

/**
 * 一次任务链规划请求的背景，供知识贡献者判断「这次该想起什么」。
 *
 * <p>2026-11-06：新增 `retrievalObjective`——**只给检索用**的临时客观串（例如附加已校验的 hp 事实）；
 * 目标文本与观察指纹仍必须用 `objective`。未提供时回退到 `objective`（旧行为零变化）。
 *
 * @param companion     为哪只同伴规划（经验按同伴隔离，必须原样透传）
 * @param objective     当前目标或当前阶段主题（也是目标指纹的唯一来源）
 * @param stage         阶段名（stage_a / stage_b / fallback）
 * @param knownFailures 已知失败事实（重试/回退时携带），是召回最相关的依据
 * @param retrievalObjective 仅检索消费的临时串；空则等于 objective
 */
public record PlanningQuery(UUID companion, String objective, String stage, List<String> knownFailures,
                            String retrievalObjective) {

    public PlanningQuery {
        objective = objective == null ? "" : objective;
        stage = stage == null ? "" : stage;
        knownFailures = knownFailures == null ? List.of() : List.copyOf(knownFailures);
        retrievalObjective = (retrievalObjective == null || retrievalObjective.isBlank())
                ? objective : retrievalObjective;
    }

    /** 兼容旧 4 参构造：检索串回退到 objective。 */
    public PlanningQuery(UUID companion, String objective, String stage, List<String> knownFailures) {
        this(companion, objective, stage, knownFailures, objective);
    }

    public static PlanningQuery of(UUID companion, String objective, String stage, List<String> knownFailures) {
        return new PlanningQuery(companion, objective, stage, knownFailures);
    }

    public static PlanningQuery of(UUID companion, String objective, String stage, List<String> knownFailures,
                                   String retrievalObjective) {
        return new PlanningQuery(companion, objective, stage, knownFailures, retrievalObjective);
    }
}
