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
            if (resp.status() == WorldObservationService.Status.PENDING) {
                Long last = REFRESH_AT.get(companionId);
                if (last == null || gameTime - last >= 200L) {
                    REFRESH_AT.put(companionId, gameTime);
                    var refreshed = svc.refresh(req);
                    if (refreshed != null) resp = refreshed;
                }
            }
            if (resp.status() == WorldObservationService.Status.ERROR) {
                return new Result(Verdict.NOT_VERIFIABLE, "观测失败：" + resp.reason(), Map.of());
            }
            boolean readable = resp.status() != WorldObservationService.Status.PENDING
                    && resp.coverage() != WorldObservationService.Coverage.UNKNOWN;
            Map<String, Integer> have = new LinkedHashMap<>();
            for (var item : resp.items()) {
                Object id = item.values().get("item");
                Object n = item.values().get("count");
                if (id instanceof String s && n instanceof Number num) have.merge(s, num.intValue(), Integer::sum);
            }
            return requireAll(have, required, readable);
        } catch (RuntimeException | LinkageError e) {
            return new Result(Verdict.NOT_VERIFIABLE, "判定异常：" + e, Map.of());
        }
    }

    private static final Map<UUID, Long> REFRESH_AT = new java.util.concurrent.ConcurrentHashMap<>();
}
