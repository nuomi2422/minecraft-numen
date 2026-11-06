package com.dwinovo.numen.worldstate.core.request;

import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;

import java.util.Locale;
import java.util.UUID;

/**
 * 一次「去取」的请求：主题 + 维度 + 目标/范围 + 过滤条件。
 *
 * <p>{@code target} 与 {@code filter} 是键的规范串；{@code bounds} 是给采集器直接用的结构化范围
 * （环境的 {@code target/filter} 都为空，{@code bounds} 为 null）。
 */
public record ObservationRequest(Topic topic, String dimension, String target, String filter, Bounds bounds) {

    public ObservationRequest {
        if (topic == null) throw new IllegalArgumentException("topic required");
        dimension = dimension == null ? "" : dimension.toLowerCase(Locale.ROOT).trim();
        target = target == null ? "" : target.trim();
        filter = filter == null ? "" : filter.trim();
        // 2026-11-06: one canonical companion key, not a box/filter alias or implicit current player.
        if (topic == Topic.SELF) {
            if (dimension.isEmpty() || !target.startsWith("player:") || target.length() != 43
                    || !filter.isEmpty() || bounds != null) {
                throw new IllegalArgumentException("self requires dimension and player:<uuid>, no filter/bounds");
            }
            UUID uuid = UUID.fromString(target.substring(7));
            target = "player:" + uuid;
        }
    }

    /** 环境快照请求：一个维度一份。 */
    public static ObservationRequest environment(String dimension) {
        return new ObservationRequest(Topic.ENVIRONMENT, dimension, "", "", null);
    }

    /** 实体计数请求：范围内的某类实体，或某类别（passive/all）。 */
    public static ObservationRequest entities(String dimension, Bounds box, String entityType, String category) {
        if (box == null) throw new IllegalArgumentException("bounds required for entities");
        String type = entityType == null || entityType.isBlank() ? null : entityType.trim();
        String cat = type != null ? null : (category == null || category.isBlank() ? "all" : category.trim());
        String filter = type != null ? "type=" + type : "category=" + cat;
        return new ObservationRequest(Topic.ENTITIES, dimension, "box=" + box.canonical(), filter, box);
    }

    /** 方块状态请求：范围内的方块（可选只匹配某方块 id / tag）。 */
    public static ObservationRequest blocks(String dimension, Bounds box, String blockFilter) {
        return blocks(dimension, box, blockFilter, 0);
    }

    /**
     * 同上，并要求采集器带回**位置样本**（每个规格最多 {@code samples} 个）。
     *
     * <p>为什么要位置：农田/采矿这类消费者不只是问"有多少"，还要问"要去哪一格"。
     * 计数与位置分开放（计数全量、样本有上限），不把整个范围搬进观测。
     */
    public static ObservationRequest blocks(String dimension, Bounds box, String blockFilter, int samples) {
        if (box == null) throw new IllegalArgumentException("bounds required for blocks");
        String f = blockFilter == null || blockFilter.isBlank() ? "" : "block=" + blockFilter.trim();
        String target = "box=" + box.canonical() + (samples > 0 ? " samples=" + samples : "");
        return new ObservationRequest(Topic.BLOCKS, dimension, target, f, box);
    }

    /**
     * 单个容器的请求（039 U3）：目标形如 {@code block:1,2,3} 或 {@code player:<uuid>}。
     *
     * <p>只认一个容器——"把附近所有箱子合成一条"是消费者的事，不是观测的事。
     */
    public static ObservationRequest containers(String dimension, String containerTarget) {
        String t = containerTarget == null ? "" : containerTarget.trim();
        if (t.isEmpty() || (!t.startsWith("block:") && !t.startsWith("player:"))) {
            throw new IllegalArgumentException("container target must be block:x,y,z or player:<uuid>");
        }
        return new ObservationRequest(Topic.CONTAINERS, dimension, t, "", null);
    }

    /** B2a data-only candidate: UUID names the companion, not an authorization grant. */
    public static ObservationRequest self(String dimension, UUID companionUUID) {
        if (companionUUID == null) throw new IllegalArgumentException("companion UUID required");
        return new ObservationRequest(Topic.SELF, dimension, "player:" + companionUUID, "", null);
    }

    /** 该请求在世界存档 {@code worldId} 下的观测键。 */
    public ObservationKey keyFor(String worldId) {
        return new ObservationKey(worldId, dimension, topic, target, filter);
    }

    public long volume() {
        return bounds == null ? 0L : bounds.volume();
    }

    public String describe() {
        StringBuilder sb = new StringBuilder(topic.id());
        if (!dimension.isEmpty()) sb.append(" @ ").append(dimension);
        if (!target.isEmpty()) sb.append(' ').append(target);
        if (!filter.isEmpty()) sb.append(" [").append(filter).append(']');
        return sb.toString();
    }
}
