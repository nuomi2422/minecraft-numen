package com.dwinovo.numen.agent.retrieval;

import java.util.List;

/**
 * 75 号 §15.5 `EntryDescriptor` —— 条目的元数据侧（可被排序/过滤/裁剪），
 * 与 `EntryBody` 的实例在世界观上**分离**（IC10：正文 revision 固定，历史可追溯
 * ⇒ 同 {@code entryRef} 可有多版 body，不同 revision 绝不混成同一版本）。
 *
 * <p><b>G5a 最小</b>：不塞 {@code publicationRef}/{@code executionRef}（归 B5/B6）。
 *
 * <p><b>G5g（2026-10-22）</b>：新增 {@link #viewOnly()} —— 「查得到」与「能拿它干活」是两件事。
 * 工具目录条目（`tool:` 前缀）恒为 {@code true}，并且**由 ref 前缀强制**（见紧凑构造器），
 * 不依赖调用方记得传 true；执行入口必须读这个字段并拒绝（{@link ExecutionGate}）。
 */
public record EntryDescriptor(
        String entryRef,
        EntryKind kind,
        String title,
        String summary,
        List<RetrievalRole> roles,
        List<String> applicableConditions,
        List<String> excludedConditions,
        List<String> evidenceRefs,
        String bodyRef,
        boolean retracted,
        Maturity maturity,
        int verifiedCount,
        /* G5g：只读条目（目录/说明书）—— 可以呈现、可以展开，但**不许**作为执行授权。 */
        boolean viewOnly) {

    public EntryDescriptor {
        roles = frozenRoles(roles);
        applicableConditions = frozen(applicableConditions);
        excludedConditions = frozen(excludedConditions);
        evidenceRefs = frozen(evidenceRefs);
        // ★ G5g Y1：`tool:` 前缀 ⇒ 恒只读。用前缀强制而不是信任入参，
        //   因为「某个调用方忘了传 true」是必然会发生的事，而它的代价是「检索即授权」。
        if (entryRef != null && entryRef.startsWith(ToolCatalogRetrievalProvider.REF_PREFIX)) {
            viewOnly = true;
        }
    }

    private static List<RetrievalRole> frozenRoles(List<RetrievalRole> v) {
        return v == null ? List.of() : List.copyOf(v);
    }

    private static List<String> frozen(List<String> v) {
        return v == null ? List.of() : List.copyOf(v);
    }
}
