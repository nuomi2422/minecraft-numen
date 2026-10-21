package com.dwinovo.numen.agent.retrieval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * G5a 最小 experience provider：把经验海的条目适配进统一检索查询。
 *
 * <p>关键行为（判据 s109cv 之后的第二轮 IC 对账）：
 * <ul>
 *   <li><b>IC08 最小实现</b>：EXECUTOR 只见「当前有效」（maturity ≥ VERIFIED 且非撤回）；
 *       PLANNER/LEARNER 见全部（含撤回作为「待核实线索」呈现 —— <b>绝不</b>冒充可执行）。</li>
 *   <li><b>无 LLM 调用</b>：适用性按 {@code applicable/excludedConditions} 与
 *       {@code query.objective()} 的字串包含命中给出 PASS / UNKNOWN / FAIL；
 *       查不到即 UNKNOWN，不再烧一次模型。</li>
 *   <li><b>唯一排序：成熟度 rank 降序 → verifiedCount 降序 → title 稳定字节序</b>；
 *       这是「可预判、可复现」的最小排序。</li>
 *   <li><b>maxCandidates 截断落 {@code gaps}</b>：「 multimedia index=max_c」不假装空=没内容（IC09）。</li>
 *   <li>entryRef 级 de-dup 交给 {@code MinimalContextAssembler}（provider 内每条只留一次）
 *       —— 它是唯一「同 ref 只出现一次」的关卡（判据 S7）。</li>
 * </ul>
 */
public final class ExperienceRetrievalProvider implements RetrievalProvider {

    private final String id;
    private final ExperienceEntrySource source;

    public ExperienceRetrievalProvider(String id, ExperienceEntrySource source) {
        if (source == null) throw new IllegalArgumentException("ExperienceEntrySource 不能为空");
        this.id = id == null ? "experience" : id;
        this.source = source;
    }

    @Override
    public String providerId() {
        return id;
    }

    @Override
    public SearchPage search(RetrievalQuery query) {
        if (query == null) {
            return SearchPage.empty(id, "null", new Gap(id, "QUERY_NULL", "query 不能为空"));
        }
        List<RetrievedEntry> all = new ArrayList<>();
        try {
            List<ExperienceEntryRead> raw = source.allEntries();
            if (raw == null) {
                return SearchPage.empty(id, query.queryId(),
                        new Gap(id, "SOURCE_NULL", "source.allEntries() 返回 null（读失败，不是空结果）"));
            }
            for (ExperienceEntryRead e : raw) {
                RetrievedEntry r = toEntry(query, e);
                if (r != null) all.add(r);
            }
        } catch (RuntimeException t) {
            return SearchPage.empty(id, query.queryId(),
                    new Gap(id, "SOURCE_THROW", t.toString()));
        }

        // 排序（稳定）：非撤回在前 → 成熟度 desc → verifiedCount desc → title 字节序稳定
        all.sort(Comparator
                .comparingInt((RetrievedEntry r) -> r.descriptor().retracted() ? 1 : 0)
                .thenComparingInt(r -> r.descriptor().maturity() == null ? -1 : -r.descriptor().maturity().rank)
                .thenComparingInt(r -> -r.descriptor().verifiedCount())
                .thenComparing(r -> r.descriptor().title() == null ? "" : r.descriptor().title(), java.util.Comparator.naturalOrder()));

        List<RetrievedEntry> kept = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        List<Gap> gaps = new ArrayList<>();
        int cap = query.budget() == null ? 0 : query.budget().maxCandidates();
        for (RetrievedEntry r : all) {
            String ref = r.descriptor().entryRef();
            if (ref == null || ref.isBlank() || ref.equals("null")) {
                gaps.add(new Gap(id, "MISSING_REF", "条目缺 entryRef：" + safeTitle(r)));
                continue;
            }
            if (seen.contains(ref)) continue;   // 同 ref 在 provider 内已出现
            if (cap > 0 && kept.size() >= cap) {
                gaps.add(new Gap(id, "MAX_CANDIDATES", "maxCandidates=" + cap + " 之后截断"));
                break;
            }
            seen.add(ref);
            kept.add(r);
        }
        boolean partial = !gaps.isEmpty();
        return new SearchPage(id, query.queryId(), partial, List.copyOf(kept), List.copyOf(gaps));
    }

    /** 把哺乳一条源条目转成 looked-up entry，按 IC08 最小角色/适用性规则；DISQUALIFIED 条目返回 null。 */
    private RetrievedEntry toEntry(RetrievalQuery query, ExperienceEntryRead e) {
        if (e == null) return null;
        if (query.role() == RetrievalRole.EXECUTOR
                && (e.retracted()
                    || e.maturity() == null
                    || e.maturity().rank < Maturity.VERIFIED.rank)) {
            return null;
        }
        if (e.bodyContent() == null && e.title() == null && e.summary() == null) {
            return null;   // 整条都空 = 没有内容可呈现
        }

        EntryKind kind = e.kind() == null ? EntryKind.UNKNOWN : e.kind();
        Maturity m = e.maturity() == null ? Maturity.OBSERVED : e.maturity();
        EntryDescriptor desc = new EntryDescriptor(
                e.entryRef() == null ? "" : e.entryRef(),
                kind,
                e.title() == null ? "" : e.title(),
                e.summary() == null ? "" : e.summary(),
                rolesFor(e),
                e.applicableConditions() == null ? List.of() : e.applicableConditions(),
                e.excludedConditions() == null ? List.of() : e.excludedConditions(),
                e.evidenceRefs() == null ? List.of() : e.evidenceRefs(),
                e.entryRef() == null ? "" : e.entryRef(),
                e.retracted(),
                m,
                e.verifiedCount());
        EntryBody body = new EntryBody(
                e.entryRef() == null ? "" : e.entryRef(),
                e.contentHash() == null ? "" : e.contentHash(),
                "text/plain",
                e.bodyContent() == null ? "" : e.bodyContent(),
                false,
                e.source() == null ? id : e.source());
        return new RetrievedEntry(desc, body, applicability(query, desc));
    }

    private List<RetrievalRole> rolesFor(ExperienceEntryRead e) {
        List<RetrievalRole> roles = new ArrayList<>();
        // 最小判定：maturity>=VERIFIED 且未撤回才可给 EXECUTOR（即派活时才可自动用）；
        //      观测 ATTEMPTED 未交付得可给 PLANNER/LEARNER（待核实线索）。
        if (!e.retracted() && e.maturity() != null && e.maturity().rank >= Maturity.VERIFIED.rank) {
            roles.add(RetrievalRole.PLANNER);
            roles.add(RetrievalRole.EXECUTOR);
            roles.add(RetrievalRole.LEARNER);
        } else {
            roles.add(RetrievalRole.PLANNER);
            roles.add(RetrievalRole.LEARNER);
        }
        return roles;
    }

    /**
     * 最小适用性：<b>命中排除条件 → FAIL</b>；<b>命中适用条件 → PASS</b>；
     * 查不到/没配条件 → UNKNOWN（不许冒充 PASS）。
     */
    private Applicability applicability(RetrievalQuery query, EntryDescriptor desc) {
        String objective = query.objective() == null ? "" : query.objective();
        for (String c : desc.excludedConditions()) {
            if (c != null && !c.isBlank() && objective.contains(c)) return Applicability.FAIL;
        }
        for (String c : desc.applicableConditions()) {
            if (c != null && !c.isBlank() && objective.contains(c)) return Applicability.PASS;
        }
        return Applicability.UNKNOWN;
    }

    private static String safeTitle(RetrievedEntry r) {
        String t = r == null || r.descriptor() == null ? null : r.descriptor().title();
        if (t == null) return "";
        String safe = t.replace('\n', ' ').replace('\r', ' ');
        return safe.length() > 24 ? safe.substring(0, 24) + "…" : safe;
    }
}
