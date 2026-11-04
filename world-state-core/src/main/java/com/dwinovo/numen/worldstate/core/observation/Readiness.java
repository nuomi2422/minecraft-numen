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
 * <h2>四态</h2>
 * <ul>
 *   <li>{@link #READY}：OK + 完整覆盖 + 所需字段都已知且新鲜 → 可以下结论（包括"没有"）；</li>
 *   <li>{@link #READY_PARTIAL}：OK + 部分覆盖，但**所需字段**都已知 → 可以说"有什么"，**不能**说"没有"；</li>
 *   <li>{@link #NOT_READY_STALE}：所需字段里有过期的 → 不可核验（需显式 refresh）；</li>
 *   <li>{@link #NOT_READY_UNKNOWN}：非 OK / 覆盖未知 / 所需字段没观测到 → 不可核验。</li>
 * </ul>
 *
 * <p>这条"PARTIAL 不能证明不存在"是 039 §4 的原话，也是 R2 最想防的假完成来源。
 */
public enum Readiness {

    READY,
    READY_PARTIAL,
    NOT_READY_STALE,
    NOT_READY_UNKNOWN;

    /** 能否据此下"不存在/为空"的结论。只有完整覆盖才配。 */
    public boolean canProveAbsence() {
        return this == READY;
    }

    /** 能否据此说"有什么、是多少"。 */
    public boolean canReportPresence() {
        return this == READY || this == READY_PARTIAL;
    }

    /**
     * 判定一次观测（或其中一条 item）的可读性。
     *
     * @param transportOk   传输是否成功（API 的 status==OK；core 层不依赖 API 枚举）
     * @param coverage      覆盖程度
     * @param requiredFields 下结论依赖的字段（空集=只依赖"观测本身存在"）
     * @param values        本条 item 已知字段 → 值
     * @param unknownFields 本条 item 未观测到的字段
     * @param staleFields   本条 item 有值但过期的字段
     */
    public static Readiness assess(boolean transportOk, Coverage coverage,
                                   Set<String> requiredFields,
                                   Set<String> knownFields,
                                   Set<String> unknownFields,
                                   Set<String> staleFields) {
        if (!transportOk) return NOT_READY_UNKNOWN;
        if (coverage == null || coverage == Coverage.UNLOADED) return NOT_READY_UNKNOWN;
        Set<String> req = requiredFields == null ? Set.of() : requiredFields;
        Set<String> known = knownFields == null ? Set.of() : knownFields;
        Set<String> unknown = unknownFields == null ? Set.of() : unknownFields;
        Set<String> stale = staleFields == null ? Set.of() : staleFields;

        for (String f : req) {
            if (unknown.contains(f) || !known.contains(f)) return NOT_READY_UNKNOWN;
        }
        for (String f : req) {
            if (stale.contains(f)) return NOT_READY_STALE;
        }
        return coverage == Coverage.PARTIAL ? READY_PARTIAL : READY;   // COMPLETE → READY
    }
}
