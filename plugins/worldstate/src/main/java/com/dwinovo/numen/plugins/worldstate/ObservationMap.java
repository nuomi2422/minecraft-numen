package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.api.observation.WorldObservationService.Coverage;
import com.dwinovo.numen.api.observation.WorldObservationService.Status;
import com.dwinovo.numen.worldstate.core.model.Freshness;

/**
 * core 的覆盖/时效 → 公开读口的四类状态（039 §4）。
 *
 * <p>抽成纯函数是为了能离线测：这段决定"这条观测能不能当事实用"，
 * 比采集器本身更容易写错，也更容易被后续改动悄悄改坏。
 *
 * <p>规矩：
 * <ul>
 *   <li><b>没有观测</b> → {@code PENDING}（要刷新；不是"世界是空的"）；</li>
 *   <li><b>覆盖不全</b>（PARTIAL/UNLOADED）→ 最多 {@code PARTIAL}，不许 OK；</li>
 *   <li><b>过期</b>（STALE/HISTORICAL）→ 最多 {@code PARTIAL}，且字段要标 stale；</li>
 *   <li>只有"完整覆盖 + 当前"才是 {@code OK + FULL}。</li>
 * </ul>
 */
final class ObservationMap {

    private ObservationMap() {}

    /**
     * @param coverage  core 覆盖（可 null）
     * @param freshness core 时效（可 null）
     * @param present   存储里有没有这条观测
     */
    record Mapped(Status status, Coverage coverage, boolean stale, String reason) {}

    static Mapped map(com.dwinovo.numen.worldstate.core.model.Coverage coverage,
                      Freshness freshness, boolean present) {
        if (!present) {
            return new Mapped(Status.PENDING, Coverage.UNKNOWN, false, "no observation yet; refresh first");
        }
        Coverage out = switch (coverage == null
                ? com.dwinovo.numen.worldstate.core.model.Coverage.UNLOADED : coverage) {
            case COMPLETE -> Coverage.FULL;
            case PARTIAL -> Coverage.PARTIAL;
            case UNLOADED -> Coverage.UNKNOWN;
        };
        boolean stale = freshness != Freshness.CURRENT;
        boolean ok = out == Coverage.FULL && !stale;
        Status status = ok ? Status.OK : Status.PARTIAL;
        String reason = ok ? null
                : (stale ? "observation is " + freshness : "coverage is " + coverage);
        return new Mapped(status, out, stale, reason);
    }
}
