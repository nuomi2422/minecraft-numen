package com.dwinovo.numen.rdd.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * P1.5 唯一规划资产口径（Planner 提示词 + PlanGuard 共用）。
 *
 * <p>背景：以前规划读"最近一次背包扫描缓存"(1s)，依赖门读 {@link AssetRegistry}（5s），两口径不一致；
 * 死亡掉装备后缓存还会残留旧装备。这里合成一个快照。
 *
 * <p><b>口径（2026-09-25 修正）</b>：缓存背包是"最后一次真实扫描"的底，
 * <b>注册表只做加法/修正，不做减法清空</b>——
 * <ul>
 *   <li>OBSERVED 的 {@code inventory_scan} 条目按注册表计数覆盖（含补零后的 0）——新观测胜过旧缓存；</li>
 *   <li>INVALID / UNKNOWN 记入 lost / unknown 供提示词显式告知，但<b>不</b>从可用中移除缓存仍持有的条目：
 *       失效往往是"死亡/掉落"的一种过时判定，而缓存是最后一次真实扫描；贸然清空会让重规划在
 *       重扫回 OBSERVED 之前误以为"两手空空"，把已有全套铁装的人重新规划回铁器时代。</li>
 *   <li>world_ 世界资产不参与"背包持有"计数（另由 world 资产渲染块提供）。</li>
 * </ul>
 * 注册表为空（尚未扫描）时退化为缓存背包，不会把"尚未观测"误判成"两手空空"。
 * 纯 JVM、可单测。
 */
public final class PlanningAssetSnapshot {

    private final Map<String, Integer> available;
    private final List<String> lost;
    private final List<String> unknown;

    private PlanningAssetSnapshot(Map<String, Integer> available, List<String> lost, List<String> unknown) {
        this.available = Collections.unmodifiableMap(new TreeMap<>(available));
        this.lost = List.copyOf(lost);
        this.unknown = List.copyOf(unknown);
    }

    /** 合成快照：cachedInventory 可为 null；registry 可为 null。 */
    public static PlanningAssetSnapshot from(Map<String, Integer> cachedInventory, AssetRegistry registry) {
        Map<String, Integer> available = new TreeMap<>();
        if (cachedInventory != null) {
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
                if (!"inventory_scan".equals(entry.observation().type())) {
                    continue;
                }
                String id = entry.assetId();
                switch (entry.status()) {
                    case OBSERVED -> {
                        Object count = entry.observation().value().get("count");
                        if (isNonNegativeInteger(count)) {
                            available.put(id, ((Number) count).intValue());
                        }
                        // 计数缺失时保留缓存原值，不清空（新观测胜过旧缓存的加法语义）
                    }
                    case INVALID -> {
                        lost.add(id);
                        // 修正（2026-09-25）：不再 available.remove(id)。INVALID 只是提示，
                        // 缓存（最后一次真实扫描）仍持有的条目照常算可用，避免重规划倒退。
                    }
                    case UNKNOWN -> {
                        unknown.add(id);
                        // 同上：UNKNOWN 只提示"先核实"，不移除缓存里仍持有的条目。
                    }
                }
            }
        }
        Collections.sort(lost);
        Collections.sort(unknown);
        return new PlanningAssetSnapshot(available, lost, unknown);
    }

    /** 规划可用背包计数（不可变、按 key 排序）。 */
    public Map<String, Integer> availableCounts() {
        return available;
    }

    /** 已被判定失去（INVALID）的背包资产 id（排序、不可变）。 */
    public List<String> lostIds() {
        return lost;
    }

    /** 状态不确定（UNKNOWN）的背包资产 id（排序、不可变）。 */
    public List<String> unknownIds() {
        return unknown;
    }

    private static boolean isNonNegativeInteger(Object count) {
        return count instanceof Number n
                && Double.isFinite(n.doubleValue())
                && n.doubleValue() >= 0
                && n.doubleValue() == (double) n.intValue();
    }
}
