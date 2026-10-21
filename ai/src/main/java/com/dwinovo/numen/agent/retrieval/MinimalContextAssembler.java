package com.dwinovo.numen.agent.retrieval;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 75 号 §15.5 `RetrievalService`（最小离线装配）—— provider 结果 → {@link ContextSnapshot}。
 *
 * <p><b>顺序（IC09）</b>：
 * ① 必知区（objective 原文）无条件完整保留；
 * ② 必知区超 maxChars → DENIED + CONSTRAINT_OVERFLOW，**不吞 goal 原文**；
 * ③ 可选目录按 provider 排序走过来，maxCandidates/maxChars 硬上限，**截断必落 truncationReasons**，
 *    没有「无声丢条目」；
 * ④ 同 {@code entryRef} 只进一条（IC10 条目去重）。
 *
 * <p>本批**不**调用 provider（取它的 `SearchPage` 由调用方代入），也不 LLM ——
 * 离线纯函数；真链路在 `RetrievalService` 真实 host 侧由 G5b 接线。
 */
public final class MinimalContextAssembler {

    private MinimalContextAssembler() {}

    public static ContextSnapshot buildContext(String snapshotId, RetrievalQuery query,
                                               List<RetrievedEntry> candidates) {
        if (query == null) {
            return new ContextSnapshot(nz(snapshotId), "", RetrievalRole.LEARNER, List.of(),
                    List.of(), List.of(new Gap("assembler", "QUERY_NULL", "query 不能为空")),
                    List.of(), ContextSnapshot.Status.DENIED);
        }
        List<String> mandatory = new ArrayList<>();
        int mandatoryChars = 0;
        String objective = query.objective() == null ? "" : query.objective();
        if (!objective.isBlank()) {
            mandatory.add(objective);
            mandatoryChars += objective.length();
        }
        int maxChars = query.budget() == null ? 0 : query.budget().maxChars();
        if (maxChars > 0 && mandatoryChars > maxChars) {
            // 必知区被自己的硬上限挤爆：DENIED -- 但 objective 原文还塞在 mandatory 里
            //（「不吞掉危险信息」，装不下只是拒绝暂停动作，不是删词）
            return new ContextSnapshot(nz(snapshotId), query.queryId(), query.role(),
                    List.copyOf(mandatory), List.of(),
                    List.of(new Gap("assembler", "CONSTRAINT_OVERFLOW",
                            "mandatory 需要 " + mandatoryChars + " 字符 > 上限 " + maxChars)),
                    List.of("mandatory_chars_overflow"), ContextSnapshot.Status.DENIED);
        }

        List<RetrievedEntry> kept = new ArrayList<>();
        List<RetrievedEntry> in = candidates == null ? List.<RetrievedEntry>of() : candidates;
        List<String> reasons = new ArrayList<>();
        List<Gap> gaps = new ArrayList<>();
        Set<String> seenRefs = new LinkedHashSet<>();
        int used = mandatoryChars;
        int maxCandidates = query.budget() == null ? 0 : query.budget().maxCandidates();
        for (RetrievedEntry e : in) {
            String ref = e.descriptor().entryRef();
            if (ref == null || ref.isBlank()) {
                gaps.add(new Gap("assembler", "MISSING_REF", "缺 entryRef，跳过"));
                continue;
            }
            if (seenRefs.contains(ref)) continue;       // 同 ref 只进一次
            if (maxCandidates > 0 && kept.size() >= maxCandidates) {
                reasons.add("max_candidates=" + maxCandidates);
                gaps.add(new Gap("assembler", "MAX_CANDIDATES", "之后截断"));
                break;
            }
            int cost = bodyCost(e);
            if (maxChars > 0 && used + cost > maxChars) {
                reasons.add("max_chars=" + maxChars);
                gaps.add(new Gap("assembler", "MAX_CHARS", "再进下一条会顶爆字符预算"));
                break;
            }
            seenRefs.add(ref);
            kept.add(e);
            used += cost;
        }
        ContextSnapshot.Status status = reasons.isEmpty() ? ContextSnapshot.Status.OK : ContextSnapshot.Status.PARTIAL;
        return new ContextSnapshot(nz(snapshotId), query.queryId(), query.role(),
                List.copyOf(mandatory), List.copyOf(kept), List.copyOf(gaps),
                List.copyOf(reasons), status);
    }

    /** 最小成本定义：title+summary+content 的字符数。 */
    private static int bodyCost(RetrievedEntry e) {
        int n = 0;
        n += len(e.descriptor().title());
        n += len(e.descriptor().summary());
        if (e.body() != null) n += len(e.body().content());
        return n;
    }

    private static int len(String s) {
        return s == null ? 0 : s.length();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
