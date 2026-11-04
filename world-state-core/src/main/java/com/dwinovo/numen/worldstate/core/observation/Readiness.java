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

    /** 传输层结果（core 层不依赖 API 枚举，这里只保留三者）。 */
    public enum Transport { OK, PENDING, ERROR }

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
        if (transport != Transport.OK) return NOT_READY_UNKNOWN;
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
        return coverage == Coverage.PARTIAL ? READY_PARTIAL : READY;
    }
}
