package com.dwinovo.numen.agent.retrieval;

import java.util.ArrayList;
import java.util.List;

/**
 * G5c 工具目录 provider：把工具/积木目录作为检索条目（`kind=PROCEDURE`）。
 *
 * <p><b>三条离线纪律（判据 S6）</b>：
 * <ul>
 *   <li>目录条目 {@code Applicability 恒 UNKNOWN}——「列在目录里」不等于「此刻该用」，
 *       检索层不许擅自升 PASS（IC09）。</li>
 *   <li>不给 {@code executionRef}（B6 发布资格批的事）——检索结果只读，
 *       目录条目不带「执行票据」字段，杜绝「检索即授权」的误读面（IC10）。</li>
 *   <li>maturity 由 source 给：离线合成 source 给 {@code VERIFIED} 让它过 EXECUTOR 门
 *       （PROCEDURE 无验证计数概念，真实分级由宿主接线批定）。</li>
 * </ul>
 *
 * <p>排序/截断/去重逻辑与 {@link ExperienceRetrievalProvider} 一致
 * （maturity desc → verifiedCount desc → title 字节序；maxCandidates 截断落 gap）。
 */
public final class ToolCatalogRetrievalProvider implements RetrievalProvider {

    /** EntryRef 路由前缀（`MinimalRetrievalService.expand` 按它找 owner）。 */
    public static final String REF_PREFIX = "tool:";

    private final ExperienceEntrySource source;

    public ToolCatalogRetrievalProvider(ToolCatalogEntrySource source) {
        // ToolCatalogEntrySource 与 ExperienceEntrySource 同形状；这里直接当 ExperienceEntrySource 用，
        // 避免为同一形状开两条 instanceof 分支。
        this.source = requireSource(source);
    }

    private static ExperienceEntrySource requireSource(Object o) {
        if (o == null) throw new IllegalArgumentException("ToolCatalogEntrySource 不能为空");
        if (o instanceof ToolCatalogEntrySource t) {
            return t::allEntries;
        }
        throw new IllegalArgumentException("需要 ToolCatalogEntrySource，得到 " + o.getClass());
    }

    @Override
    public String providerId() {
        return "tool-catalog";
    }

    @Override
    public SearchPage search(RetrievalQuery query) {
        if (query == null) {
            return SearchPage.empty(providerId(), "null", new Gap(providerId(), "QUERY_NULL", "query 不能为空"));
        }
        List<RetrievedEntry> all = new ArrayList<>();
        try {
            List<ExperienceEntryRead> raw = source.allEntries();
            if (raw == null) {
                return SearchPage.empty(providerId(), query.queryId(),
                        new Gap(providerId(), "SOURCE_NULL", "source.allEntries() 返回 null（读失败，不是空结果）"));
            }
            for (ExperienceEntryRead e : raw) {
                RetrievedEntry r = toEntry(e);
                if (r != null) all.add(r);
            }
        } catch (RuntimeException t) {
            return SearchPage.empty(providerId(), query.queryId(),
                    new Gap(providerId(), "SOURCE_THROW", t.toString()));
        }

        all.sort(java.util.Comparator
                .comparingInt((RetrievedEntry r) -> r.descriptor().retracted() ? 1 : 0)
                .thenComparingInt(r -> r.descriptor().maturity() == null ? -1 : -r.descriptor().maturity().rank)
                .thenComparingInt(r -> -r.descriptor().verifiedCount())
                .thenComparing(r -> r.descriptor().title() == null ? "" : r.descriptor().title()));

        List<RetrievedEntry> kept = new ArrayList<>();
        java.util.Set<String> seen = new java.util.TreeSet<>();
        List<Gap> gaps = new ArrayList<>();
        int cap = query.budget() == null ? 0 : query.budget().maxCandidates();
        for (RetrievedEntry r : all) {
            String ref = r.descriptor().entryRef();
            if (ref == null || ref.isBlank()) {
                gaps.add(new Gap(providerId(), "MISSING_REF", "条目缺 entryRef"));
                continue;
            }
            if (seen.contains(ref)) continue;
            if (cap > 0 && kept.size() >= cap) {
                gaps.add(new Gap(providerId(), "MAX_CANDIDATES", "maxCandidates=" + cap + " 之后截断"));
                break;
            }
            seen.add(ref);
            kept.add(r);
        }
        return new SearchPage(providerId(), query.queryId(), !gaps.isEmpty(),
                List.copyOf(kept), List.copyOf(gaps));
    }

    private RetrievedEntry toEntry(ExperienceEntryRead e) {
        if (e == null) return null;
        if (e.bodyContent() == null && e.title() == null && e.summary() == null) return null;
        EntryKind kind = e.kind() == null ? EntryKind.PROCEDURE : e.kind();
        Maturity m = e.maturity() == null ? Maturity.VERIFIED : e.maturity();
        EntryDescriptor d = new EntryDescriptor(
                e.entryRef() == null ? "" : e.entryRef(),
                kind,
                e.title() == null ? "" : e.title(),
                e.summary() == null ? "" : e.summary(),
                List.of(RetrievalRole.PLANNER, RetrievalRole.EXECUTOR, RetrievalRole.LEARNER),
                e.applicableConditions() == null ? List.of() : e.applicableConditions(),
                e.excludedConditions() == null ? List.of() : e.excludedConditions(),
                e.evidenceRefs() == null ? List.of() : e.evidenceRefs(),
                e.entryRef() == null ? "" : e.entryRef(),
                e.retracted(),
                m,
                e.verifiedCount());
        EntryBody b = new EntryBody(
                e.entryRef() == null ? "" : e.entryRef(),
                e.contentHash() == null ? "" : e.contentHash(),
                "text/plain",
                e.bodyContent() == null ? "" : e.bodyContent(),
                false,
                e.source() == null ? providerId() : e.source());
        // ★ 判据 S6：目录条目 Applicability 恒 UNKNOWN —— 检索层不许擅自升 PASS。
        return new RetrievedEntry(d, b, Applicability.UNKNOWN);
    }
}
