package com.dwinovo.numen.plugins.worldstate;

import com.dwinovo.numen.worldstate.core.refresh.RefreshResult;
import com.dwinovo.numen.worldstate.core.request.ObservationRequest;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 常驻环境块的周期刷新（2026-11-06）。
 *
 * <h2>为什么需要它</h2>
 * 常驻注入里的环境块（RddEnvBlock）读的是本 store 的 ENVIRONMENT 观测，自己只标
 * CURRENT/过期；而全仓此前只有"按需 refresh"，没有周期刷新。ENVIRONMENT 的 TTL 是 30 秒
 * （{@code TopicFreshnessPolicy}），于是常驻块会在 30 秒后自然变旧，模型才发现要 look_around。
 * 这里按固定节拍刷新一次，让它保持 CURRENT。
 *
 * <h2>为什么失败不清旧值</h2>
 * refresh 只在采集成功后写观测（见 {@code RefreshService}）；失败时旧观测原样留着，
 * 由年龄自然变 STALE。<b>不伪造一条新事实</b>——"没刷上"必须表现为"旧"，不能表现为"刚采的"。
 *
 * <h2>为什么不每 tick 刷</h2>
 * 每 tick 刷一次 = 20 次/秒读世界时间与天气，收益为零；而按 {@link #INTERVAL_MS} 刷，
 * 即便某一轮失败，下一轮仍在 TTL 内，常驻块不会因为一次抖动就掉到过期。
 */
final class EnvironmentRefresher {

    private static final Logger LOG = LoggerFactory.getLogger("worldstate");

    /**
     * 刷新间隔。ENVIRONMENT 的 TTL 是 30s，取 20s 留 10s 余量（约 1/3 个 TTL）：
     * 单轮失败拖到下一轮时，仍有一次在 TTL 内成功的机会。15–30 秒都合理，20 秒是折中。
     *
     * <p>这是<b>宿主节拍</b>，不是采集精度承诺；未经实机测性能，不声称开销。
     */
    static final long INTERVAL_MS = 20_000L;

    private final WorldStateService service;
    /** 下一次允许扫描的绝对时间（毫秒）。每 tick 只做一次比较，到点才真的去刷。 */
    private long nextSweepAtMs = Long.MIN_VALUE;

    EnvironmentRefresher(WorldStateService service) {
        this.service = service;
    }

    /**
     * 服务端 tick 入口（见 {@code WorldStateMod} 的 ServerTickEvent.Post）。
     *
     * @return 本轮真正刷新成功的维度数；0 = 没到点 / 没有维度 / 全部失败
     */
    int tick(MinecraftServer server, long nowMs) {
        if (server == null || service == null) return 0;
        if (nowMs < nextSweepAtMs) return 0;
        // 先推进节拍再刷：失败也按"这一轮已经试过"算，避免失败时每 tick 重试把日志刷爆。
        nextSweepAtMs = nowMs + INTERVAL_MS;
        int refreshed = 0;
        for (var level : server.getAllLevels()) {
            String dimension = level.dimension().location().toString();
            RefreshResult result = service.refresh(server, ObservationRequest.environment(dimension));
            if (result.success()) {
                refreshed++;
            } else {
                // 旧观测不动，年龄继续走 → 自然过期。这里只留痕，不补一条假事实。
                LOG.debug("[worldstate] environment refresh skipped for {}: {}", dimension, result.message());
            }
        }
        return refreshed;
    }
}
