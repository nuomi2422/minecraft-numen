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

    /** G5g：是否按 objective 做相关性过滤（host 接线用 true，离线合成用 false）。 */
    private final boolean objectiveFilter;

    /** G5g：过滤模式下最多放几条目录条目（防止 64 个工具把经验挤掉）。 */
    private static final int FILTERED_CAP = 2;

    public ToolCatalogRetrievalProvider(ToolCatalogEntrySource source) {
        this(source, false);
    }

    /**
     * @param objectiveFilter true = 只在 objective 命中工具名/描述时才放行，且最多 {@value #FILTERED_CAP} 条。
     *                        <b>为什么需要它</b>：真工具表有 64 个，执行/规划块只有 800–2400 字预算，
     *                        无脑全塞会**挤掉经验条目**——那恰好会破坏刚验过的 V4。
     */
    public ToolCatalogRetrievalProvider(ToolCatalogEntrySource source, boolean objectiveFilter) {
        // ToolCatalogEntrySource 与 ExperienceEntrySource 同形状；这里直接当 ExperienceEntrySource 用，
        // 避免为同一形状开两条 instanceof 分支。
        this.source = requireSource(source);
        this.objectiveFilter = objectiveFilter;
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

        // ★ G5g：过滤模式 —— 只在 objective 命中时放行，且硬上限 FILTERED_CAP。
        if (objectiveFilter) {
            String obj = query.objective() == null ? "" : query.objective().toLowerCase(java.util.Locale.ROOT);
            if (obj.isBlank()) {
                // 没有 objective（执行侧当前就是空串）⇒ 一条都不放。
                // 不是「没实现」，是**故意的**：无相关性时塞目录只会挤掉经验。
                return SearchPage.empty(providerId(), query.queryId());
            }
            List<RetrievedEntry> hits = new ArrayList<>();
            for (RetrievedEntry r : all) {
                // ★ G5g-2：正文也进 haystack —— 中文触发词放在正文里（不占块内显示位）。
                //   2026-10-22 实机：current_task 是中文、工具描述是英文，只比 title/summary 永远不命中。
                String hay = (r.descriptor().entryRef() + " "
                        + r.descriptor().title() + " "
                        + r.descriptor().summary() + " "
                        + (r.body() == null || r.body().content() == null ? "" : r.body().content()))
                        .toLowerCase(java.util.Locale.ROOT);
                if (matchesAnyToken(obj, hay)) {
                    hits.add(r);
                    if (hits.size() >= FILTERED_CAP) break;
                }
            }
            return new SearchPage(providerId(), query.queryId(), false, List.copyOf(hits), List.of());
        }

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

    /**
     * objective 里有没有哪个词（≥2 字符）出现在 hay 里。
     *
     * <p>刻意做得笨：不做分词、不做同义词 —— 这一步只负责「别把 64 个工具全塞进去」，
     * 真正的相关性排序归 B6/后续批。宁可漏（少放一条），不可多（挤掉经验）。
     */
    private static boolean matchesAnyToken(String objectiveLower, String hayLower) {
        // ① 空格/标点分词命中（英文工具名、中文短语都可能走这条）
        for (String tok : objectiveLower.split("[\\s，,。.、；;：:！!？?/|]+")) {
            if (tok.length() >= 2 && hayLower.contains(tok)) {
                return true;
            }
        }
        // ② 中文没有空格：整句会被当成一个词，所以用 2 字滑窗。
        //    2026-10-22 实机就是栽在这里：objective=「走到据点中心并停留」整句比不中「走到」。
        for (int i = 0; i + 2 <= objectiveLower.length(); i++) {
            String bi = objectiveLower.substring(i, i + 2);
            if (isCjk(bi.charAt(0)) && isCjk(bi.charAt(1)) && hayLower.contains(bi)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }

    private RetrievedEntry toEntry(ExperienceEntryRead e) {        if (e == null) return null;
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
                e.verifiedCount(),
                true);   // ★ G5g Y1：目录条目恒只读（EntryDescriptor 还会按 tool: 前缀再强制一次）
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

    // ═══ G5g Y3：`tool:` 条目现在能展开正文（此前是 default 的 UNSUPPORTED）═══

    /**
     * 展开一条工具目录条目：把「工具名 + 描述 + 参数 schema」当正文返回。
     *
     * <p><b>只读</b>：不解析参数、不派任务、不碰工具注册表 —— 这里只从 source 的条目里取
     * 已经写好的 {@code bodyContent}。要执行请走任务入口，且会被 {@link ExecutionGate} 拦。
     *
     * <p>截断是显式的（{@code truncated=true} + 省略号），不把半句话当完整正文。
     */
    @Override
    public Result<EntryBody> expand(EntryRef ref, ExpansionRequest request) {
        if (ref == null || ref.value() == null || ref.value().isBlank()) {
            return new Result.Err<>("BAD_REF", "EntryRef 不能为空");
        }
        List<ExperienceEntryRead> raw;
        try {
            raw = source.allEntries();
        } catch (RuntimeException t) {
            return new Result.Err<>("SOURCE_THROW", t.toString());
        }
        if (raw == null) {
            return new Result.Err<>("SOURCE_NULL", "source.allEntries() 返回 null（读失败，不是空结果）");
        }
        for (ExperienceEntryRead e : raw) {
            if (e == null || !ref.value().equals(e.entryRef())) continue;
            String body = e.bodyContent() == null ? "" : e.bodyContent();
            int max = request == null ? 0 : request.maxChars();
            boolean truncated = max > 0 && body.length() > max;
            if (truncated) body = body.substring(0, max) + "…";
            return new Result.Ok<>(new EntryBody(
                    e.entryRef(),
                    e.contentHash() == null ? "" : e.contentHash(),
                    "text/plain",
                    body,
                    truncated,
                    providerId()));
        }
        return new Result.Err<>("NOT_FOUND", "目录里没有 " + ref.value() + "（不是「没有正文」，是没有这条）");
    }
}
