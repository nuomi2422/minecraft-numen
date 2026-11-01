package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.policy.HomeStage;
import com.dwinovo.numen.rdd.policy.HomeSummary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把「基地事实」压成执行请求里的四行（167 §B3）。
 *
 * <p>数据全部来自**已有**观测，不新增账本：
 * <ul><li>{@code world_base} 资产（由 {@code RddWorldAssetObserver.observeBase} 写入，含 facilities / important_items / 观测时刻）；</li>
 * <li>基地阶段由 {@link HomeStage} 从事实算，不由模型写。</li></ul>
 *
 * <p>刻意保持"只读、只陈述"：这里不判断该不该放行高风险（那是 RiskGate 的事，167 §B6），
 * 也不替模型挑下一步的工具。
 */
final class RddHomeFacts {

    private RddHomeFacts() {}

    /** 备用清单里只报"能救命的那几类"，避免把 24 条库存全塞进上下文。 */
    private static final List<String> RESERVE_KEYS = List.of(
            "minecraft:iron_pickaxe", "minecraft:iron_sword", "minecraft:diamond_pickaxe",
            "minecraft:cooked_beef", "minecraft:bread", "minecraft:torch",
            "minecraft:white_bed", "minecraft:water_bucket");

    static String blockFor(AssetRegistry registry) {
        return blockFor(registry, System.currentTimeMillis());
    }

    static String blockFor(AssetRegistry registry, long nowMillis) {
        AssetRegistry.AssetEntry base = freshestBase(registry);
        if (base == null) {
            // 没观测到基地不等于没有基地：只报 NO_HOME，并明确 reserve 未知（不写 0）。
            return HomeSummary.block(HomeSummary.lines(HomeStage.NO_HOME, null, null, null, null,
                    null, null, null, null, nowMillis));
        }
        Map<String, Object> v = base.observation().value();
        long asOf = base.observation().observedAt();
        String dimension = String.valueOf(v.getOrDefault("dimension", "unknown"));
        String pos = v.get("x") + " " + v.get("y") + " " + v.get("z");

        int facilityCount = v.get("facilities") instanceof List<?> l ? l.size() : 0;
        boolean houseUsable = facilityCount > 0;

        Map<String, Integer> reserve = new LinkedHashMap<>();
        if (v.get("important_items") instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if (!(e.getValue() instanceof Number n)) continue;
                String k = String.valueOf(e.getKey());
                if (RESERVE_KEYS.contains(k)) reserve.put(k, n.intValue());
            }
        }
        boolean reserveSufficient = !reserve.isEmpty();
        boolean litProtected = false;   // 保护/光照判据尚未产生事实，明确保持 false 而不是假装通过

        HomeStage stage = HomeStage.of(true, houseUsable, true, reserveSufficient, reserveSufficient, litProtected);
        String blocking = stage == HomeStage.ESTABLISHED ? null
                : (litProtected ? null : "lit_protection_unverified");
        String next = switch (stage) {
            case NO_HOME, CANDIDATE -> "settlement_register(core_house)";
            case SHELTERED -> "bind_respawn_at_bed";
            case RESPAWN_BOUND -> "scan_reserve_containers";
            case STOCKED -> "verify_lighting_and_protection";
            default -> null;
        };
        return HomeSummary.block(HomeSummary.lines(stage,
                String.valueOf(v.getOrDefault("label", base.assetId())),
                dimension, pos, asOf,
                reserve.isEmpty() ? null : reserve, asOf,
                blocking, next, nowMillis));
    }

    private static AssetRegistry.AssetEntry freshestBase(AssetRegistry registry) {
        if (registry == null) return null;
        AssetRegistry.AssetEntry best = null;
        for (var e : RddAssetContext.worldAssets(registry)) {
            Object kind = e.observation().value().get("kind");
            if (!"base".equals(String.valueOf(kind))) continue;
            if (best == null || e.observation().observedAt() > best.observation().observedAt()) best = e;
        }
        return best;
    }
}
