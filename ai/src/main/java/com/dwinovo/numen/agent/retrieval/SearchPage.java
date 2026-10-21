package com.dwinovo.numen.agent.retrieval;

import java.util.List;

/**
 * 75 号 §15.5 `SearchPage` —— 一次 provider 检索的分页结果。
 *
 * <p>provider 失败 → 输出 {@code gaps}；其他 provider 可返回 <b>PARTIAL</b>
 * 结果（IC09），「没召回」与「读不到」分开。
 */
public record SearchPage(String providerId, String queryId, boolean partial,
                         List<RetrievedEntry> entries, List<Gap> gaps) {
    public SearchPage {
        entries = entries == null ? List.of() : List.copyOf(entries);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
    }

    /** 一个「读不到/没读到」的空页；它不是「空结果当成功」——看 gaps 里有没有显式报错。 */
    public static SearchPage empty(String providerId, String queryId, Gap... causes) {
        return new SearchPage(providerId, queryId, causes != null && causes.length > 0, List.of(), List.of(causes));
    }
}
