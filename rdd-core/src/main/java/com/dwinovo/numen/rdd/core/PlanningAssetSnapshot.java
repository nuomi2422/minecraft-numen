package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.AssetStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * P1.5 唯一规划资产口径（Planner 提示词 + PlanGuard 共用）。
 *
 * <p>实时扫描是当前持有的候选真相，但<b>失效、未知、过期或死亡前的旧扫描不能继续贡献
 * available 计数</b>。注册表中的 {@code inventory_scan} 状态因此是安全边界，而不只是提示：
 * INVALID 进 lost，UNKNOWN 进 unknown，两者都从 available/claims 中移除。
 *
 * <p>生产宿主使用带时钟/死亡窗口的重载；旧两参/三参重载保留源码兼容，仍执行 INVALID/UNKNOWN
 * 覆盖，但不自行猜测扫描 TTL。世界资产不参与背包计数，由 {@link #worldClaims(AssetRegistry)} 提供。
 */
public final class PlanningAssetSnapshot {

    /** 背包观测是否足以贡献 available；未知与过期都必须显式暴露。 */
    public enum InventoryStatus { OBSERVED, UNKNOWN, EXPIRED, PRE_DEATH_STALE }

    private final Map<String, Integer> available;
    private final List<String> lost;
    private final List<String> unknown;
    /** 最近一次实时扫描的系统毫秒时间（可为 null = 未观测到扫描时刻），随 claims() 暴露。 */
    private final Long scanAtMillis;
    private final InventoryStatus inventoryStatus;

    private PlanningAssetSnapshot(Map<String, Integer> available, List<String> lost, List<String> unknown,
                                  Long scanAtMillis, InventoryStatus inventoryStatus) {
        this.available = Collections.unmodifiableMap(new TreeMap<>(available));
        this.lost = List.copyOf(lost);
        this.unknown = List.copyOf(unknown);
        this.scanAtMillis = scanAtMillis;
        this.inventoryStatus = inventoryStatus;
    }

    /** 兼容旧调用：执行 INVALID/UNKNOWN 覆盖，但不自行猜测扫描 TTL。 */
    public static PlanningAssetSnapshot from(Map<String, Integer> cachedInventory, AssetRegistry registry) {
        return from(cachedInventory, null, registry);
    }

    /** 兼容旧调用：执行 INVALID/UNKNOWN 覆盖；扫描时刻只用于 claims 元字段。 */
    public static PlanningAssetSnapshot from(Map<String, Integer> cachedInventory, Long scanAtMillis, AssetRegistry registry) {
        return compose(cachedInventory, scanAtMillis, registry, InventoryStatus.OBSERVED, true);
    }

    /**
     * 生产快照：显式判定扫描是否过期、是否早于当前死亡窗口。
     *
     * @param nowMillis        当前系统毫秒
     * @param maxAgeMillis     扫描最大年龄；负数表示不启用 TTL（仅供兼容/测试）
     * @param deathAtMillis    最近一次死亡的系统毫秒；null = 不知死亡时刻
     * @param deathWindowMillis 死亡窗口长度；窗口内且扫描早于死亡时，旧计数一律降为 UNKNOWN
     */
    public static PlanningAssetSnapshot from(Map<String, Integer> cachedInventory, Long scanAtMillis,
                                              AssetRegistry registry, long nowMillis, long maxAgeMillis,
                                              Long deathAtMillis, long deathWindowMillis) {
        InventoryStatus status;
        if (deathAtMillis != null && deathWindowMillis >= 0 && nowMillis >= deathAtMillis
                && nowMillis - deathAtMillis <= deathWindowMillis
                && (scanAtMillis == null || scanAtMillis < deathAtMillis)) {
            status = InventoryStatus.PRE_DEATH_STALE;
        } else if (scanAtMillis == null) {
            status = InventoryStatus.UNKNOWN;
        } else {
            long age = nowMillis - scanAtMillis;
            status = age < 0 ? InventoryStatus.UNKNOWN
                    : maxAgeMillis >= 0 && age > maxAgeMillis ? InventoryStatus.EXPIRED
                    : InventoryStatus.OBSERVED;
        }
        return compose(cachedInventory, scanAtMillis, registry, status, status == InventoryStatus.OBSERVED);
    }

    private static PlanningAssetSnapshot compose(Map<String, Integer> cachedInventory, Long scanAtMillis,
                                                  AssetRegistry registry, InventoryStatus inventoryStatus,
                                                  boolean mayUseCachedCounts) {
        Map<String, Integer> available = new TreeMap<>();
        if (mayUseCachedCounts && cachedInventory != null) {
            for (Map.Entry<String, Integer> e : cachedInventory.entrySet()) {
                if (e.getKey() != null && e.getValue() != null && e.getValue() >= 0) {
                    available.put(e.getKey(), e.getValue());
                }
            }
        }
        List<String> lost = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        if (registry != null) {
            for (AssetRegistry.AssetEntry entry : registry.snapshot()) {
                if (!"inventory_scan".equals(entry.observation().type())) continue;
                String id = entry.assetId();
                switch (entry.status()) {
                    case OBSERVED -> {
                        if (!mayUseCachedCounts) unknown.add(id);
                    }
                    case INVALID -> lost.add(id);
                    case UNKNOWN -> unknown.add(id);
                }
            }
        }
        if (!mayUseCachedCounts && cachedInventory != null) {
            for (String id : cachedInventory.keySet()) {
                if (id != null) unknown.add(id);
            }
        }
        for (String id : lost) available.remove(id);
        for (String id : unknown) available.remove(id);
        Collections.sort(lost);
        Collections.sort(unknown);
        return new PlanningAssetSnapshot(available, lost, unknown.stream().distinct().toList(), scanAtMillis, inventoryStatus);
    }

    /** 是否留有扫描时刻；只表明曾扫描，不证明当前仍新鲜。 */
    public boolean hasInventoryScanTime() { return scanAtMillis != null; }

    /** 当前背包观测状态；生产快照不会把 UNKNOWN/EXPIRED/PRE_DEATH_STALE 当作已观测。 */
    public InventoryStatus inventoryStatus() { return inventoryStatus; }

    /** 是否允许把本快照的背包计数当作当前观测；false 时 available 一定为空。 */
    public boolean isInventoryKnown() { return inventoryStatus == InventoryStatus.OBSERVED; }

    /** 规划可用背包计数（不可变、按 key 排序）。 */
    public Map<String, Integer> availableCounts() {
        return available;
    }

    /**
     * 持背资产声明（带元字段）：每条 = {@code live_scan} 来源 + 最近一次扫描时刻（可为 null）。
     * <b>只覆盖实时持有且未被 INVALID/UNKNOWN/过期/死亡旧扫描降级的背包资产</b>。
     */
    public Map<String, AssetClaim> claims() {
        Map<String, AssetClaim> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : available.entrySet()) {
            out.put(e.getKey(), new AssetClaim(e.getKey(), e.getValue(), AssetClaim.SOURCE_LIVE_SCAN, scanAtMillis));
        }
        return Collections.unmodifiableMap(out);
    }

    /**
     * 世界资产声明（注册表持久化的 world_* 条目，OBSERVED 才出证言）：
     * 每条 = {@code world_registry} 来源 + 该观测发生时刻（observedAt）。
     * count 语义为"在场"（presence=1），具体内容见观测 record 的 value。
     */
    public static Map<String, AssetClaim> worldClaims(AssetRegistry registry) {
        if (registry == null) return Collections.emptyMap();
        Map<String, AssetClaim> out = new LinkedHashMap<>();
        for (AssetRegistry.AssetEntry entry : registry.snapshot()) {
            if (entry.status() != AssetStatus.OBSERVED) continue;
            String type = entry.observation().type();
            if (type != null && type.startsWith("world_")) {
                out.put(entry.assetId(), new AssetClaim(entry.assetId(), 1,
                        AssetClaim.SOURCE_WORLD_REGISTRY, entry.observation().observedAt()));
            }
        }
        return Collections.unmodifiableMap(out);
    }

    /** 已被判定失去（INVALID）的背包资产 id（排序、不可变）。 */
    public List<String> lostIds() {
        return lost;
    }

    /** 状态不确定（UNKNOWN、过期或死亡前旧扫描）的背包资产 id（排序、不可变）。 */
    public List<String> unknownIds() {
        return unknown;
    }
}
