package com.dwinovo.numen.api.observation;

import java.util.List;
import java.util.Set;

/**
 * 「这次观测能不能拿来下结论」（042 R2 契约 1/2）。
 *
 * <h2>为什么在 api 层</h2>
 * 它解释的正是 {@link WorldObservationService} 自己的 `Status`/`Coverage` 语义，
 * 放在 api 里，所有插件都能直接看到（rdd 走 `compileOnly api`，worldstate 走插件约定），
 * 不需要把 core 打进每个插件（那会踩重复包的历史坑）。
 *
 * <h2>五态</h2>
 * <ul>
 *   <li>{@link #READY}：OK + FULL + 所需字段都已知且新鲜 → 可以下结论（包括"没有"）；</li>
 *   <li>{@link #READY_PARTIAL}：只拿到一部分（Status.PARTIAL 或 Coverage.PARTIAL）→ 可以说"有什么"，**不能**说"没有"；</li>
 *   <li>{@link #NOT_READY_STALE}：所需字段过期 → 需显式 refresh；</li>
 *   <li>{@link #NOT_READY_PENDING}：还没有观测 → 需显式 refresh（与 ERROR 不同）；</li>
 *   <li>{@link #NOT_READY_UNKNOWN}：失败 / 覆盖未知 / 所需字段没观测到 / 调用方没给所需字段。</li>
 * </ul>
 *
 * <p><b>注意</b>：`Status.PARTIAL` 在实现里同时代表"覆盖不全"和"数据过期"两种情况
 * （`ObservationMap`：`ok = FULL && !stale`）。所以只看 Status 分不出来，必须看 item 的 staleFields——
 * 这也正是本类要求逐条 item 判断的原因。
 */
public enum Readiness {

    READY,
    READY_PARTIAL,
    NOT_READY_STALE,
    NOT_READY_PENDING,
    NOT_READY_UNKNOWN;

    /** 能否据此下"不存在/为空"的结论。只有 OK+FULL 才配。 */
    public boolean canProveAbsence() {
        return this == READY;
    }

    /** 能否据此说"有什么、是多少"。 */
    public boolean canReportPresence() {
        return this == READY || this == READY_PARTIAL;
    }

    /** 是否需要（以及是否值得）触发一次显式 refresh。 */
    public boolean wantsRefresh() {
        return this == READY_PARTIAL || this == NOT_READY_STALE || this == NOT_READY_PENDING;
    }

    /**
     * 判定一次观测（或其中一条 item）的可读性。
     *
     * @param status         传输状态（API 自己的枚举，不再按名字映射）
     * @param coverage       覆盖程度（API 自己的枚举）
     * @param requiredFields **下结论依赖的字段**；传 {@code null} 表示"调用方没想清楚"，判 NOT_READY_UNKNOWN
     *                       （fail-closed）；只有显式 {@code Set.of()} 才表示"我确认不需要字段"
     * @param knownFields    本条 item 已知字段
     * @param unknownFields  本条 item 未观测到的字段（当前实现恒为空，缺字段靠 knownFields 缺项表达）
     * @param staleFields    本条 item 有值但过期的字段
     */
    public static Readiness assess(WorldObservationService.Status status,
                                   WorldObservationService.Coverage coverage,
                                   Set<String> requiredFields,
                                   Set<String> knownFields,
                                   Set<String> unknownFields,
                                   Set<String> staleFields) {
        if (status == WorldObservationService.Status.PENDING) return NOT_READY_PENDING;
        // Status.PARTIAL = 只拿到一部分：绝不能据此断言"没有"，最多算 READY_PARTIAL
        boolean transportPartial = status == WorldObservationService.Status.PARTIAL;
        if (status != WorldObservationService.Status.OK && !transportPartial) return NOT_READY_UNKNOWN;
        if (requiredFields == null) return NOT_READY_UNKNOWN;   // fail-closed：忘了传不等于不需要
        if (coverage == null || coverage == WorldObservationService.Coverage.UNKNOWN) return NOT_READY_UNKNOWN;

        Set<String> known = knownFields == null ? Set.of() : knownFields;
        Set<String> unknown = unknownFields == null ? Set.of() : unknownFields;
        Set<String> stale = staleFields == null ? Set.of() : staleFields;

        for (String f : requiredFields) {
            if (unknown.contains(f) || !known.contains(f)) return NOT_READY_UNKNOWN;
        }
        for (String f : requiredFields) {
            if (stale.contains(f)) return NOT_READY_STALE;
        }
        if (transportPartial) return READY_PARTIAL;
        return coverage == WorldObservationService.Coverage.PARTIAL ? READY_PARTIAL : READY;
    }

    /**
     * 多条 item 的合并规则：**取最坏**（不是多数票）。
     *
     * <p>空列表由 status+coverage 决定——空集合正是假完成高发处（"箱子没扫到"≠"箱子是空的"）。
     */
    public static Readiness worstOf(List<Readiness> items,
                                    WorldObservationService.Status status,
                                    WorldObservationService.Coverage coverage) {
        if (items == null || items.isEmpty()) {
            return canProveAbsence(status, coverage) ? READY : NOT_READY_UNKNOWN;
        }
        Readiness worst = READY;
        for (Readiness r : items) {
            if (r == null) return NOT_READY_UNKNOWN;
            if (severity(r) > severity(worst)) worst = r;
        }
        return worst;
    }

    /**
     * 任一 item 想要刷新就 true。
     *
     * <p>合并会丢恢复信息：一条 PENDING + 一条 UNKNOWN → worstOf=UNKNOWN（该报错），
     * 但那条 PENDING 明明该刷新。契约：`worstOf` 管"能否下结论"，本方法管"要不要刷新"。
     */
    public static boolean anyWantsRefresh(List<Readiness> items) {
        if (items == null) return false;
        for (Readiness r : items) {
            if (r != null && r.wantsRefresh()) return true;
        }
        return false;
    }

    /**
     * **证否**是 response 级结论：空容器没有 item 可评估，"这箱子是空的"必须单独问一次。
     */
    public static boolean canProveAbsence(WorldObservationService.Status status,
                                          WorldObservationService.Coverage coverage) {
        return assess(status, coverage, Set.of(), Set.of(), Set.of(), Set.of()).canProveAbsence();
    }

    private static int severity(Readiness r) {
        switch (r) {
            case NOT_READY_UNKNOWN: return 4;
            // PENDING（压根没采到）排在 STALE（有值但旧）之前：报错时说"没采过"比说"过期"更准确
            case NOT_READY_PENDING: return 3;
            case NOT_READY_STALE: return 2;
            case READY_PARTIAL: return 1;
            default: return 0;
        }
    }
}
