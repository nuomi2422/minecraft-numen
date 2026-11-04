package com.dwinovo.numen.worldstate.core.observation;

import com.dwinovo.numen.worldstate.core.model.Coverage;

import java.util.Set;

/**
 * 「这次观测能不能拿来下结论」（042 R2 契约 1/2 的纯逻辑落点）。
 *
 * <h2>为什么要有它</h2>
 * 现行消费者各写各的：`RddCondition` 用 `status != PENDING && coverage != UNKNOWN` 当可读，
 * `RddDangerFacts` 用 `status != ERROR && !changes.isEmpty()`——**两套口径，且都把 PARTIAL 当可读**。
 * 契约要求的是「按所需字段逐项判断」。这里把判断收成一个函数，消费者各拿各的字段来问。
 *
 * <h2>五态（PENDING 单独一档）</h2>
 * <ul>
 *   <li>{@link #READY}：OK + 完整覆盖 + 所需字段都已知且新鲜 → 可以下结论（包括"没有"）；</li>
 *   <li>{@link #READY_PARTIAL}：OK + 部分覆盖，所需字段都已知 → 可以说"有什么"，**不能**说"没有"；</li>
 *   <li>{@link #NOT_READY_STALE}：所需字段过期 → 需显式 refresh；</li>
 *   <li>{@link #NOT_READY_PENDING}：还没有观测 → 需显式 refresh（与 ERROR 不同）；</li>
 *   <li>{@link #NOT_READY_UNKNOWN}：失败 / 覆盖未加载 / 所需字段没观测到 / **调用方没给所需字段**。</li>
 * </ul>
 *
 * <p>PENDING 与 ERROR 分开是刻意的：前者该刷新，后者该报错，压成一个会逼消费者再去看 status，
 * 等于把"各写一套"从可读判断挪到恢复判断。
 */
public enum Readiness {

    READY,
    READY_PARTIAL,
    NOT_READY_STALE,
    NOT_READY_PENDING,
    NOT_READY_UNKNOWN;

    /**
     * 传输层结果（core 层不依赖 API 枚举，值与 API 的 `Status` **一一对应**）。
     *
     * <p>四值 `{OK, PARTIAL, PENDING, ERROR}` 必须与 `WorldObservationService.Status` 完全同构——
     * 少一个（比如漏 PARTIAL）就会逼消费者自己决定"它算 OK 还是 ERROR"，于是又变成各写一套
     * （审计 C 的原话：洞只是从"可读判断"挪到了"枚举转换"）。
     */
    public enum Transport {
        OK,
        PARTIAL,
        PENDING,
        ERROR;

        /** 按 API 枚举名映射；不认识的名字一律按 ERROR（fail-closed，绝不默认 OK）。 */
        public static Transport fromApiName(String name) {
            if (name == null) return ERROR;
            switch (name.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "OK": return OK;
                case "PARTIAL": return PARTIAL;
                case "PENDING": return PENDING;
                default: return ERROR;
            }
        }
    }

    /**
     * 按 API 的 `Coverage` 名映射到 core 的 {@link Coverage}。
     *
     * <p>`FULL→COMPLETE`、`PARTIAL→PARTIAL`、`UNKNOWN→UNLOADED`；
     * 不认识的名字按 UNLOADED（最保守：什么都不能证明）。
     */
    public static Coverage coverageFromApiName(String name) {
        if (name == null) return Coverage.UNLOADED;
        switch (name.trim().toUpperCase(java.util.Locale.ROOT)) {
            case "FULL": return Coverage.COMPLETE;
            case "PARTIAL": return Coverage.PARTIAL;
            default: return Coverage.UNLOADED;
        }
    }

    /**
     * 多条 item 的合并规则（**worst-of**，不是多数票）。
     *
     * <p>为什么是 worst-of：一条链路上任何一条 item 不可核验，"整批"就不可核验——
     * 少数服从多数会把"有 2 条好的"当成"整体没问题"，正好是假完成的入口。
     *
     * <p>优先级（从坏到好）：`NOT_READY_UNKNOWN > NOT_READY_PENDING > NOT_READY_STALE > READY_PARTIAL > READY`。
     * **空列表不再默认 READY**：由 transport+coverage 决定（只有 OK+COMPLETE 才算可下结论）。
     */
    public static Readiness worstOf(java.util.List<Readiness> items, Transport transport, Coverage coverage) {
        if (items == null || items.isEmpty()) {
            // 空列表正是假完成最容易发生的地方（箱子没扫到 → items 空 → 若返回 READY 就会报"箱子是空的"）。
            // 所以由构造保证：只有 OK+COMPLETE 的空集合才算 READY，其余一律 NOT_READY_UNKNOWN。
            return canProveAbsence(transport, coverage) ? READY : NOT_READY_UNKNOWN;
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
     * <p>为什么不能只看 {@link #worstOf}：合并会丢恢复信息——三条 item 里一条 PENDING、
     * 一条 UNKNOWN、一条 READY，worstOf=UNKNOWN（该报错），但那条 PENDING 明明该刷新。
     * 契约：`worstOf` 回答"能不能下结论"，本方法回答"要不要刷新"，两者不许互相代替。
     */
    public static boolean anyWantsRefresh(java.util.List<Readiness> items) {
        if (items == null) return false;
        for (Readiness r : items) {
            if (r != null && r.wantsRefresh()) return true;
        }
        return false;
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

    /**
     * **证否**是 response 级结论，不是 item 级。
     *
     * <p>"这箱子是空的"在 items 为空时根本没有 item 可评估——所以证否必须单独问一次：
     * 传空所需字段，只认 {@link #canProveAbsence()}（即 OK + COMPLETE）。
     */
    public static boolean canProveAbsence(Transport transport, Coverage coverage) {
        return assess(transport, coverage, java.util.Set.of(), java.util.Set.of(), java.util.Set.of(),
                java.util.Set.of()).canProveAbsence();
    }

    /** 能否据此下"不存在/为空"的结论。只有完整覆盖才配。 */
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
     * 判定一次观测的可读性。
     *
     * @param transport      传输结果
     * @param coverage       覆盖程度
     * @param requiredFields **下结论依赖的字段**；传 {@code null} 表示"调用方没想清楚"，判 NOT_READY_UNKNOWN
     *                       （fail-closed）；只有显式 {@code Set.of()} 才表示"我确认不需要字段"
     * @param knownFields    本条 item 已知字段
     * @param unknownFields  本条 item 未观测到的字段
     * @param staleFields    本条 item 有值但过期的字段
     */
    public static Readiness assess(Transport transport, Coverage coverage,
                                   Set<String> requiredFields,
                                   Set<String> knownFields,
                                   Set<String> unknownFields,
                                   Set<String> staleFields) {
        if (transport == Transport.PENDING) return NOT_READY_PENDING;
        // PARTIAL 传输 = 只拿到一部分：绝不能据此断言"没有"，最多算 READY_PARTIAL
        boolean transportPartial = transport == Transport.PARTIAL;
        if (transport != Transport.OK && !transportPartial) return NOT_READY_UNKNOWN;
        if (requiredFields == null) return NOT_READY_UNKNOWN;   // fail-closed：忘了传不等于不需要
        if (coverage == null || coverage == Coverage.UNLOADED) return NOT_READY_UNKNOWN;

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
        return coverage == Coverage.PARTIAL ? READY_PARTIAL : READY;
    }
}
