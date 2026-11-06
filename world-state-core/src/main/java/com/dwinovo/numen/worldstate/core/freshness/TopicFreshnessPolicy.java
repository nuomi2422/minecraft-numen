package com.dwinovo.numen.worldstate.core.freshness;

import com.dwinovo.numen.worldstate.core.model.Freshness;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.Observation;

import java.util.EnumMap;
import java.util.Map;

/**
 * 按主题 TTL 判定新鲜度。天气、动物位置、箱子内容变化速度不同，<b>不能全用同一个时间</b>，
 * 所以 TTL 按主题配置。
 *
 * <p>{@link Freshness#HISTORICAL}（重启后载入的动态观测）优先于年龄判定——即使时间戳还在 TTL 内，
 * 它也是上个进程看到的，不能冒充本轮实时结果。
 */
public final class TopicFreshnessPolicy implements FreshnessPolicy {

    private final Map<Topic, Long> ttlMillis = new EnumMap<>(Topic.class);

    public TopicFreshnessPolicy() {
    }

    public static TopicFreshnessPolicy defaults() {
        TopicFreshnessPolicy p = new TopicFreshnessPolicy();
        p.withTtl(Topic.ENVIRONMENT, 30_000L);
        p.withTtl(Topic.ENTITIES, 30_000L);
        p.withTtl(Topic.BLOCKS, 60_000L);
        p.withTtl(Topic.CONTAINERS, 120_000L);
        p.withTtl(Topic.FACILITIES, 120_000L);
        // 2026-11-06：SELF 的 TTL 从 1000ms 提到 3000ms（与既有主题同一量级）。
        // 原因：1000ms 短于真实 MCP 消费者的一次往返，出现"刚采就过期"——
        // 案例：外部消费者对同伴 a43d753e-…（overworld）refresh self 成功后约 1 秒再 read 已判 STALE；
        // 同一次 environment 的 validUntil 是 +30000；跨主题 batch_read(environment+self,
        // max_age_ms=30000, max_skew_ms=5000) 直接报 "sample expired before batch delivery"。
        // 放宽到 3000ms 不等于"允许旧数据"：10 tick 自动采样约 500ms 就会覆盖一次，
        // 并且不为了过 batch 去放宽 max_skew 或改一致性判据。
        // 仍是候选值：未经实机性能验证，不声称"够用"。
        p.withTtl(Topic.SELF, 3_000L);
        return p;
    }

    public TopicFreshnessPolicy withTtl(Topic topic, long millis) {
        ttlMillis.put(topic, millis);
        return this;
    }

    public long ttlOf(Topic topic) {
        return ttlMillis.getOrDefault(topic, 60_000L);
    }

    @Override
    public Freshness classify(Observation observation, long nowMs) {
        if (observation == null) return Freshness.UNKNOWN;
        if (observation.freshness() == Freshness.HISTORICAL) return Freshness.HISTORICAL;
        // 2026-11-05：时钟回拨/缺时间时 ageMillis 会截成 0；不能把未来或无效时间当刚采集。
        if (nowMs <= 0L || observation.epochMs() <= 0L || observation.epochMs() > nowMs) {
            return Freshness.UNKNOWN;
        }
        long age = observation.ageMillis(nowMs);
        return age <= ttlOf(observation.key().topic()) ? Freshness.CURRENT : Freshness.STALE;
    }
}
