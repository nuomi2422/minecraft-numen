package com.dwinovo.numen.agent.retrieval;

import java.util.List;

/**
 * G5a 检索查询（75 号 §15.5 `RetrievalQuery` 的最小可用版）。
 *
 * <p>缺省扩展（后续批）保留的槽位先不给：`chain`/`planVersion`/`nodeId` 由 G5b 接进
 * 现实任务链时补；`metadata`/扩展字段加 record 字段是**破坏性变化**，所以这里只放
 * 判据需要的最小集合。
 *
 * <p>`contextMode`：<b>显式区分「idle 查询」</b>——无目标闲时也得标出来，
 * 不假装与任务相关（IC08）。
 */
public record RetrievalQuery(
        String queryId,
        String companionId,
        String worldId,
        RetrievalRole role,
        String objective,
        List<String> knownFailures,
        List<String> factSnapshotRefs,
        List<String> demandRefs,
        int capabilityVersion,
        String callerAuthorizationRef,
        RetrievalBudget budget,
        String contextMode) {

    public RetrievalQuery {
        knownFailures = frozen(knownFailures);
        factSnapshotRefs = frozen(factSnapshotRefs);
        demandRefs = frozen(demandRefs);
    }

    private static List<String> frozen(List<String> v) {
        return v == null ? List.of() : List.copyOf(v);
    }

    /** 明确的 idle 查询（无目标；不可当找任务相关内容）。 */
    public static RetrievalQuery idle(String queryId, String companionId, RetrievalBudget budget) {
        return new RetrievalQuery(queryId, companionId, "", RetrievalRole.LEARNER,
                "", List.of(), List.of(), List.of(), 1, "", budget, "IDLE");
    }
}
