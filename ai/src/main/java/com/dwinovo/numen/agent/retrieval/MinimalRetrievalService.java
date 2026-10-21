package com.dwinovo.numen.agent.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * G5c `RetrievalService` 的最小实现：多 provider 合并 + 统一 expand 路由。
 *
 * <p><b>buildContext 编排（判据 S1/S2/S3）</b>：
 * <ol>
 *   <li>问<b>每个</b>注册 provider 要 {@code SearchPage}；</li>
 *   <li>provider 返带 gap 的 PARTIAL 页 → gap <b>原样</b>进最终 snapshot；
 *       它的条目照常进合并池（<b>部分失败不算全局失败</b>，IC09）；</li>
 *   <li>provider 抛异常 → 变 {@code PROVIDER_THROW} gap，不冒泡（IC09）；</li>
 *   <li>合并候选 → {@link MinimalContextAssembler#buildContext}（预算/必知区/跨 provider
 *       entryRef 去重全复用装配器）。</li>
 * </ol>
 *
 * <p><b>expand 路由（判据 S4/S5）</b>：离线约定按前缀找 owner ——
 * {@code "tool:"} → tool-catalog provider；其余 → experience provider。
 * 真实 revision 路由留 host 批。找不到 owner → {@code Err("NO_OWNER")}；
 * owner 只有 default expand → 转发它的 {@code Err("UNSUPPORTED")}（不吞）。
 */
public final class MinimalRetrievalService implements RetrievalService {

    private static final AtomicLong SNAP_SEQ = new AtomicLong();

    private final RetrievalRegistry registry;

    public MinimalRetrievalService(RetrievalRegistry registry) {
        if (registry == null) throw new IllegalArgumentException("registry 不能为空");
        this.registry = registry;
    }

    @Override
    public ContextSnapshot buildContext(RetrievalQuery query) {
        List<RetrievedEntry> merged = new ArrayList<>();
        List<Gap> providerGaps = new ArrayList<>();
        for (RetrievalProvider p : registry.all()) {
            SearchPage page;
            try {
                page = p.search(query);
            } catch (RuntimeException t) {
                providerGaps.add(new Gap(p.providerId(), "PROVIDER_THROW", t.toString()));
                continue;
            }
            if (page == null) {
                providerGaps.add(new Gap(p.providerId(), "PAGE_NULL", "search 返回 null"));
                continue;
            }
            merged.addAll(page.entries());
            providerGaps.addAll(page.gaps());
        }
        ContextSnapshot snapshot = MinimalContextAssembler.buildContext(
                "snap-" + SNAP_SEQ.incrementAndGet(), query, merged);
        // provider 侧 gap 与装配器 gap 合并进最终 snapshot（部分失败要显式可见）
        if (providerGaps.isEmpty()) {
            return snapshot;
        }
        List<Gap> allGaps = new ArrayList<>(snapshot.gaps());
        allGaps.addAll(providerGaps);
        ContextSnapshot.Status status = snapshot.status() == ContextSnapshot.Status.DENIED
                ? ContextSnapshot.Status.DENIED
                : ContextSnapshot.Status.PARTIAL;
        return new ContextSnapshot(snapshot.snapshotId(), snapshot.queryId(), snapshot.role(),
                snapshot.mandatoryConstraints(), snapshot.directory(), List.copyOf(allGaps),
                snapshot.truncationReasons(), status);
    }

    @Override
    public Result<EntryBody> expand(EntryRef ref, ExpansionRequest request) {
        if (ref == null || ref.value() == null || ref.value().isBlank()) {
            return new Result.Err<>("BAD_REF", "EntryRef 不能为空");
        }
        RetrievalProvider owner = ownerOf(ref.value());
        if (owner == null) {
            return new Result.Err<>("NO_OWNER", "没有 provider 认领 " + ref.value());
        }
        // 转发（不吞 UNSUPPORTED —— 调用方要知道是 owner 没实现，而不是入口丢了请求）
        return owner.expand(ref, request);
    }

    /** 前缀路由：`tool:` → tool-catalog；其余 → experience。真实 revision 路由留 host 批。 */
    private RetrievalProvider ownerOf(String ref) {
        if (ref.startsWith(ToolCatalogRetrievalProvider.REF_PREFIX)) {
            return registry.byId("tool-catalog").orElse(null);
        }
        if (ref.startsWith("exp:")) {
            return registry.byId("experience").orElse(null);
        }
        // 没有可识别前缀 → 不猜 owner（宁可 NO_OWNER 也不把别人的正文当他的）
        return null;
    }
}
