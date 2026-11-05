package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.NumenPlugins;
import com.dwinovo.numen.api.observation.WorldObservationService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 用统一观测回答"要件齐了没有"（039 U3 的正题）。
 *
 * <h2>为什么要单独一个类</h2>
 * 039 最想防的是**假完成**：读不到、只读了一部分、或者拿着很久以前的快照，都不许当成
 * "已经满足"。所以判定的产物不是 boolean，而是三态 {@link Verdict}：
 * MATCHED / NOT_MATCHED / NOT_VERIFIABLE（带原因）。
 *
 * <p>判定只读观测（{@code containers:player:<uuid>}），不自己扫背包——扫描是采集器的事。
 *
 * <p><b>线程</b>：只在服务端 tick 调用（要读世界的那一步由采集器完成，这里只读缓存/触发刷新）。
 */
final class RddCondition {

    /** 三态判定：只有 MATCHED 才算满足；NOT_VERIFIABLE 绝不能被下游当成满足。 */
    enum Verdict {
        MATCHED,
        NOT_MATCHED,
        NOT_VERIFIABLE
    }

    /** 判定结果（原因给人看，也进事件）。 */
    record Result(Verdict verdict, String reason, Map<String, Integer> have) {}

    private RddCondition() {}

    /**
     * 是否需要至少各 1 件（"整套"口径）。
     *
     * <p>这是纯函数：{@code have} 是观测到的物品计数，{@code readable} 表示这次观测
     * 是否可以当事实用（OK/PARTIAL 且非过期）。规则：
     * <ul>
     *   <li>不可读 → NOT_VERIFIABLE（不许因为"没看见"就说"没有"）；</li>
     *   <li>可读且每样都 ≥1 → MATCHED；</li>
     *   <li>可读但有缺口 → NOT_MATCHED。</li>
     * </ul>
     */
    /**
     * 用 {@link com.dwinovo.numen.api.observation.Readiness} 下结论（042 R2）。
     *
     * <h2>两条路不许混</h2>
     * <ul>
     *   <li><b>MATCHED（说"有"）</b>：逐条 item 判 canReportPresence 即可；</li>
     *   <li><b>NOT_MATCHED（说"缺"）</b>：缺的物品**根本不在 items 里**，没有 item 可评，
     *       所以只能走 **response 级** {@code canProveAbsence}（OK+FULL）。</li>
     * </ul>
     * 反过来实现（用"所有存在的 item 都 READY"推 NOT_MATCHED）会把最常见的一种情况判错：
     * 背包缺铁锭 + 覆盖完整 → 会被判成 NOT_VERIFIABLE，而不是"缺"。
     */
    /**
     * 判定只需要 Readiness 的三个事实。单独抽成 record，是为了让**测试不依赖 api 模块**——
     * 把 api:common 加进本插件的测试运行时会让 AdvancementTickWiringRegressionTest 的
     * "TaskResult 必须来自本次编译产物"断言失效（父加载器先命中了 api 里的同名类）。
     */
    record Observability(boolean canReportPresence, boolean stale, boolean pending, boolean unknown) {
        static Observability from(com.dwinovo.numen.api.observation.Readiness r) {
            return new Observability(r.canReportPresence(),
                    r == com.dwinovo.numen.api.observation.Readiness.NOT_READY_STALE,
                    r == com.dwinovo.numen.api.observation.Readiness.NOT_READY_PENDING,
                    !r.canReportPresence()
                            && r != com.dwinovo.numen.api.observation.Readiness.NOT_READY_STALE
                            && r != com.dwinovo.numen.api.observation.Readiness.NOT_READY_PENDING);
        }
    }

    static Result decide(Observability obs,
                         Map<String, Integer> have, List<String> required, boolean canProveAbsence) {
        Map<String, Integer> snapshot = have == null ? Map.of() : new LinkedHashMap<>(have);
        if (required == null || required.isEmpty()) {
            return new Result(Verdict.NOT_VERIFIABLE, "没有声明要件", snapshot);
        }
        if (obs.pending()) {
            return new Result(Verdict.NOT_VERIFIABLE, "还没有观测（PENDING）", snapshot);
        }
        if (obs.stale()) {
            return new Result(Verdict.NOT_VERIFIABLE, "所需字段已过期（STALE），需刷新", snapshot);
        }
        if (!obs.canReportPresence()) {
            return new Result(Verdict.NOT_VERIFIABLE, "观测不可用或覆盖不足", snapshot);
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String item : required) {
            if (snapshot.getOrDefault(item, 0) <= 0) missing.add(item);
        }
        if (missing.isEmpty()) {
            return new Result(Verdict.MATCHED, "全部齐了：" + required.size() + " 项", snapshot);
        }
        // 说"缺"必须走 response 级证否——缺失物没有 item 可评
        if (!canProveAbsence) {
            return new Result(Verdict.NOT_VERIFIABLE,
                    "没看到 " + missing + "，但覆盖不足以断言'缺'", snapshot);
        }
        return new Result(Verdict.NOT_MATCHED, "缺 " + missing, snapshot);
    }

    static Result requireAll(Map<String, Integer> have, List<String> required, boolean readable) {
        Map<String, Integer> snapshot = have == null ? Map.of() : new LinkedHashMap<>(have);
        if (!readable) {
            return new Result(Verdict.NOT_VERIFIABLE, "背包观测不可用（缺失/过期/覆盖不全）", snapshot);
        }
        if (required == null || required.isEmpty()) {
            return new Result(Verdict.NOT_VERIFIABLE, "没有声明要件", snapshot);
        }
        java.util.List<String> missing = new java.util.ArrayList<>();
        for (String item : required) {
            int n = snapshot.getOrDefault(item, 0);
            if (n <= 0) missing.add(item);
        }
        if (missing.isEmpty()) {
            return new Result(Verdict.MATCHED, "全部齐了：" + required.size() + " 项", snapshot);
        }
        return new Result(Verdict.NOT_MATCHED, "缺 " + missing, snapshot);
    }

    /**
     * 从统一观测读背包并判定（服务端 tick 调用）。
     *
     * <p>观测缺席/过期就触发一次显式 refresh（10 秒限频），**仍不可用就 NOT_VERIFIABLE**，
     * 绝不回退成"自己扫背包"——那就又变成两套口径了。
     */
    static Result evaluate(UUID companionId, String dimension, long gameTime, List<String> required) {
        try {
            WorldObservationService svc = NumenPlugins.capability(WorldObservationService.class);
            if (svc == null) {
                return new Result(Verdict.NOT_VERIFIABLE, "观测能力未注册", Map.of());
            }
            String target = "player:" + companionId;
            var req = new WorldObservationService.Request("rdd-condition", dimension, "containers",
                    Map.of("container", target), 0L, 64, 0L);
            var resp = svc.read(req);
            if (resp == null) {
                return new Result(Verdict.NOT_VERIFIABLE, "观测返回空", Map.of());
            }
            // 042 R2：先按 Readiness 判"能不能下结论"，再决定要不要刷新
            var agg = assess(resp);
            if (agg.wantsRefresh()) {
                Long last = REFRESH_AT.get(companionId);
                if (last == null || gameTime - last >= 200L) {
                    REFRESH_AT.put(companionId, gameTime);
                    var refreshed = svc.refresh(req);
                    if (refreshed != null) {
                        resp = refreshed;
                        agg = assess(resp);      // 刷完重判一次
                    }
                }
            }
            if (resp.status() == WorldObservationService.Status.ERROR) {
                return new Result(Verdict.NOT_VERIFIABLE, "观测失败：" + resp.reason(), Map.of());
            }
            Map<String, Integer> have = new LinkedHashMap<>();
            for (var item : resp.items()) {
                Object id = item.values().get("item");
                Object n = item.values().get("count");
                if (id instanceof String s && n instanceof Number num) have.merge(s, num.intValue(), Integer::sum);
            }
            boolean canProveAbsence = com.dwinovo.numen.api.observation.Readiness
                    .canProveAbsence(resp.status(), resp.coverage());
            return decide(Observability.from(agg), have, required, canProveAbsence);
        } catch (RuntimeException | LinkageError e) {
            return new Result(Verdict.NOT_VERIFIABLE, "判定异常：" + e, Map.of());
        }
    }

    /**
     * 逐条 item 判可读性，再按 worst-of 合并（042 R2 契约 1/2）。
     *
     * <p>注意：`Status.PARTIAL` 在 API 里**同时**代表"覆盖不全"和"数据过期"（ObservationMap），
     * 所以不能只看 status——过期信息在 item 的 staleFields 里。
     */
    private static com.dwinovo.numen.api.observation.Readiness assess(
            WorldObservationService.Response resp) {
        var required = java.util.Set.of("item", "count");   // slots 不是 item 字段（012 附录 G.1）
        java.util.List<com.dwinovo.numen.api.observation.Readiness> per = new java.util.ArrayList<>();
        for (var item : resp.items()) {
            per.add(com.dwinovo.numen.api.observation.Readiness.assess(
                    resp.status(), resp.coverage(), required,
                    item.values().keySet(), item.unknownFields(), item.staleFields()));
        }
        return com.dwinovo.numen.api.observation.Readiness
                .worstOf(per, resp.status(), resp.coverage());
    }

    private static final Map<UUID, Long> REFRESH_AT = new java.util.concurrent.ConcurrentHashMap<>();
}
