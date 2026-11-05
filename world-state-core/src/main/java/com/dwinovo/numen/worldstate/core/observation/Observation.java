package com.dwinovo.numen.worldstate.core.observation;

import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Origin;

/**
 * 一条观测记录——「某次看到的东西」，不是宣称永远正确的世界真相。
 *
 * <p>不可变 record：读侧拿到的快照不会被写侧改动，跨线程读安全。
 * 时间双记录（游戏 tick + 实际毫秒）——tick 用于与世界对齐，毫秒用于算年龄/TTL。
 *
 * <p>2026-11-05：同一 JVM 重开存档也会换服务器会话；仅看时间或 worldId 会把上一轮快照当当前。
 * 空会话明确表示 unknown，不把旧构造伪装成当前会话。
 *
 * @param key        观测键（世界/维度/主题/目标/过滤）
 * @param payload    有类型的结果
 * @param source     采集器 id，或其它模块 id
 * @param origin     直接观测还是模块提供
 * @param gameTick   采集时的游戏 tick
 * @param epochMs    采集时的实际时间（毫秒）
 * @param coverage   覆盖情况
 * @param freshness  写入时的新鲜度（载入的历史观测为 HISTORICAL）；读取时会按 TTL 重算
 * @param revision   观测修订号（按被采纳的请求先后单调递增，防止旧刷新覆盖新观测）
 * @param dataVersion 载荷数据格式版本（采集器自己的）
 * @param serverSession 采集时的服务器会话；空串表示未知，不能通过严格会话读取成为当前
 */
public record Observation(ObservationKey key, ObservationPayload payload, String source, Origin origin,
                          long gameTick, long epochMs, Coverage coverage, Freshness freshness,
                          long revision, int dataVersion, String serverSession) {

    public Observation {
        if (serverSession == null || serverSession.isBlank()) serverSession = "";
    }

    /** 兼容旧调用方：缺少会话证据就是 unknown，不能从时间戳猜会话。 */
    public Observation(ObservationKey key, ObservationPayload payload, String source, Origin origin,
                       long gameTick, long epochMs, Coverage coverage, Freshness freshness,
                       long revision, int dataVersion) {
        this(key, payload, source, origin, gameTick, epochMs, coverage, freshness, revision, dataVersion, "");
    }

    public long ageMillis(long nowMs) {
        return Math.max(0L, nowMs - epochMs);
    }

    public Observation withFreshness(Freshness next) {
        return new Observation(key, payload, source, origin, gameTick, epochMs, coverage, next,
                revision, dataVersion, serverSession);
    }
}
