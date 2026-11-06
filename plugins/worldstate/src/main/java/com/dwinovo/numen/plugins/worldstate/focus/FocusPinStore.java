package com.dwinovo.numen.plugins.worldstate.focus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 单进程焦点表：挂在现有观测服务生命周期里，按 (companion, world, session) 归属；
 * 不落盘、不新观测缓存；到期/换世界/换会话自动失效。上限 4（038 建议 2+4，这里取显式焦点 4）。
 */
public final class FocusPinStore {

    public static final int MAX_PINS_PER_COMPANION = 4;
    /** 技术默认（非主人决定）：120s 见 038 §3.1 的短期焦点，上限 4 取自其“2 自动+4 显式”建议；2026-11-06。 */
    public static final long DEFAULT_TTL_MS = 120_000L;
    public static final long MIN_TTL_MS = 1_000L;
    public static final long MAX_TTL_MS = 600_000L;

    private final AtomicLong seq = new AtomicLong();
    private final Map<UUID, List<FocusPin>> pins = new LinkedHashMap<>();

    public synchronized FocusPin pin(UUID companion, String worldId, String sessionId, String dimension,
                                     String topic, long nowMs, long ttlMs, long maxAgeMs, int limit, int maxItems) {
        if (companion == null) return null;
        pruneExpired(companion, nowMs);
        // 2026-11-06（038）：换世界/换会话/换主体维度时真清理旧焦点，而不是只在查询时过滤。
        List<FocusPin> existing = pins.get(companion);
        if (existing != null) {
            existing.removeIf(p -> !p.worldId().equals(worldId) || !p.sessionId().equals(sessionId));
        }
        List<FocusPin> list = pins.computeIfAbsent(companion, k -> new ArrayList<>());
        if (list.stream().anyMatch(p -> p.topic().equals(topic) && p.dimension().equals(dimension))) {
            return list.stream().filter(p -> p.topic().equals(topic) && p.dimension().equals(dimension)).findFirst().orElse(null);
        }
        if (list.size() >= MAX_PINS_PER_COMPANION) return null;
        long ttl = Math.max(MIN_TTL_MS, Math.min(MAX_TTL_MS, ttlMs <= 0 ? DEFAULT_TTL_MS : ttlMs));
        FocusPin p = new FocusPin("pin-" + seq.incrementAndGet(), companion.toString(), worldId, sessionId,
                dimension, topic, nowMs, nowMs + ttl, maxAgeMs, limit, maxItems);
        list.add(p);
        return p;
    }

    public synchronized boolean unpin(UUID companion, String pinId) {
        if (companion == null || pinId == null) return false;
        List<FocusPin> list = pins.get(companion);
        return list != null && list.removeIf(p -> p.id().equals(pinId));
    }

    /** 当前有效焦点；世界/会话/主体不符或过期的都剔除。 */
    public synchronized List<FocusPin> active(UUID companion, String worldId, String sessionId, long nowMs) {
        pruneExpired(companion, nowMs);
        return pins.getOrDefault(companion, List.of()).stream()
                .filter(p -> p.worldId().equals(worldId) && p.sessionId().equals(sessionId))
                .toList();
    }

    /** 只清过期；世界/会话不符的只是不返回（换世界后它们自然过期，避免一次误查就删掉焦点）。 */
    private void pruneExpired(UUID companion, long nowMs) {
        List<FocusPin> list = pins.get(companion);
        if (list == null) return;
        list.removeIf(p -> !p.alive(nowMs));
        if (list.isEmpty()) pins.remove(companion);
    }
}
