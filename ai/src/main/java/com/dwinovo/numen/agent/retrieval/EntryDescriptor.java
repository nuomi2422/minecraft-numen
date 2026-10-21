package com.dwinovo.numen.agent.retrieval;

import java.util.List;

/**
 * 75 号 §15.5 `EntryDescriptor` —— 条目的元数据侧（可被排序/过滤/裁剪），
 * 与 `EntryBody` 的实例在世界观上**分离**（IC10：正文 revision 固定，历史可追溯
 * ⇒ 同 {@code entryRef} 可有多版 body，不同 revision 绝不混成同一版本）。
 *
 * <p><b>G5a 最小</b>：不塞 {@code publicationRef}/{@code executionRef}（归 B5/B6）。
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
        int verifiedCount) {

    public EntryDescriptor {
        roles = frozenRoles(roles);
        applicableConditions = frozen(applicableConditions);
        excludedConditions = frozen(excludedConditions);
        evidenceRefs = frozen(evidenceRefs);
    }

    private static List<RetrievalRole> frozenRoles(List<RetrievalRole> v) {
        return v == null ? List.of() : List.copyOf(v);
    }

    private static List<String> frozen(List<String> v) {
        return v == null ? List.of() : List.copyOf(v);
    }
}
