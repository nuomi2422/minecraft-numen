package com.dwinovo.numen.worldstate.core.query;

import com.dwinovo.numen.worldstate.core.freshness.FreshnessPolicy;
import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.provider.CollectionContext;
import com.dwinovo.numen.worldstate.core.refresh.RefreshResult;
import com.dwinovo.numen.worldstate.core.refresh.RefreshService;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import com.dwinovo.numen.worldstate.core.store.ObservationStore;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * 查询服务：统一对外。调用方不需要知道底下用哪个采集器、缓存怎么存。
 *
 * <p><b>读与取在接口上明确分开</b>：
 * <ul>
 *   <li>{@link #read} 只读已有观测，<b>永不扫描世界</b>；没有就明确返回没有。</li>
 *   <li>{@link #refresh} / {@link #refreshAsync} 主动采集，完成后写入观测并返回本次结果。</li>
 * </ul>
 * 两者都<b>不修改游戏世界</b>；刷新只更新本模块自己的缓存/存档。
 */
public final class QueryService {

    private final ObservationStore store;
    private final FreshnessPolicy freshnessPolicy;
    private final RefreshService refreshService;
    private final LongSupplier nowMs;

    public QueryService(ObservationStore store, FreshnessPolicy freshnessPolicy,
                        RefreshService refreshService, LongSupplier nowMs) {
        this.store = store;
        this.freshnessPolicy = freshnessPolicy;
        this.refreshService = refreshService;
        this.nowMs = nowMs == null ? System::currentTimeMillis : nowMs;
    }

    /** 兼容旧读口：仅按原有新鲜度策略判断；真实宿主应使用带会话的严格重载。 */
    public ReadResult read(String worldId, ObservationRequest request) {
        return read(worldId, null, request, false);
    }

    /**
     * 2026-11-05：只读已有观测，且只有已知的同一服务器会话才可能 CURRENT。
     * 案例：同一 JVM 重开同一存档时 TTL 仍未过；只比较 worldId 或两个空串都会误认当前。
     */
    public ReadResult read(String worldId, String serverSession, ObservationRequest request) {
        return read(worldId, serverSession, request, true);
    }

    private ReadResult read(String worldId, String serverSession, ObservationRequest request, boolean strictSession) {
        ObservationKey key = request.keyFor(worldId);
        Optional<Observation> found = store.get(key);
        if (found.isEmpty()) return ReadResult.missing(key);

        Observation observation = found.get();
        long now = nowMs.getAsLong();
        Freshness freshness = strictSession && !sameKnownSession(serverSession, observation.serverSession())
                ? Freshness.HISTORICAL : freshnessPolicy.classify(observation, now);
        long age = observation.ageMillis(now);
        return ReadResult.present(observation, freshness, age, observation.coverage(),
                describe(observation, freshness, age));
    }

    private static boolean sameKnownSession(String expected, String observed) {
        return expected != null && !expected.isBlank() && !"unknown".equalsIgnoreCase(expected)
                && expected.equals(observed);
    }

    /** 同步刷新（宿主工具用）。 */
    public RefreshResult refresh(CollectionContext ctx, ObservationRequest request) {
        return refreshService.refreshNow(ctx, request);
    }

    /** 异步刷新（纯逻辑/离线；相同键合并）。 */
    public CompletableFuture<RefreshResult> refreshAsync(CollectionContext ctx, ObservationRequest request,
                                                        long timeoutMillis) {
        return refreshService.submit(ctx, request, timeoutMillis);
    }

    private static String describe(Observation observation, Freshness freshness, long age) {
        StringBuilder sb = new StringBuilder();
        sb.append(observation.key().topic().id()).append(" 观测于 ").append(age).append("ms 前");
        switch (freshness) {
            case CURRENT -> sb.append("（当前）");
            case STALE -> sb.append("（已过期，建议刷新）");
            case HISTORICAL -> sb.append("（历史观测：从磁盘载入、会话不匹配或未知，内容无法确认，请刷新）");
            default -> sb.append("（未知）");
        }
        if (observation.coverage() != null && !observation.coverage().canProveAbsence()) {
            sb.append("；覆盖=").append(observation.coverage())
              .append("，只观察到部分范围，不能据此断言总数或'没有'");
        }
        return sb.toString();
    }
}
