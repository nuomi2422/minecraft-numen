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
        return blockFor(registry, null, System.currentTimeMillis());
    }

    static String blockFor(AssetRegistry registry, Map<String, Integer> inventory) {
        return blockFor(registry, inventory, System.currentTimeMillis());
    }

    static String blockFor(AssetRegistry registry, Map<String, Integer> inventory, long nowMillis) {
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
        // 167 B5：把「观测到但没备货」与「根本没观测到」分开。
        // 读数是否新鲜由 HomeSummary 统一判（超过 stale 就降级 UNKNOWN），这里只决定传不传。
        boolean observedItems = v.get("important_items") instanceof Map<?, ?>;
        // 167 B5：够不够不再由"有没有东西"决定，统一按 ReserveKit.MINIMUM 判。
        boolean reserveSufficient = com.dwinovo.numen.rdd.policy.HomeStage.reserveSufficient(
                observedItems ? reserve : null);
        java.util.List<String> shortfall = com.dwinovo.numen.rdd.policy.ReserveKit.shortfall(
                observedItems ? reserve : null);
        boolean litProtected = false;   // 保护/光照判据尚未产生事实，明确保持 false 而不是假装通过

        HomeStage stage = HomeStage.of(true, houseUsable, true, observedItems, reserveSufficient, litProtected);
        String blocking;
        if (stage == HomeStage.ESTABLISHED) {
            blocking = null;
        } else if (!observedItems) {
            blocking = "reserve_unobserved";
        } else if (!reserveSufficient) {
            // 读到但不够：把缺口写进 blocking，模型才知道"补什么"，而不是只知道"缺"。
            blocking = shortfall.isEmpty() ? "reserve_missing" : "reserve_short:" + String.join(",", shortfall);
        } else {
            blocking = "lit_protection_unverified";
        }
        // next 必须与 blocking 自洽：blocking 说"箱子里没备货"，next 就不该再叫人去扫同一个箱子。
        String next = switch (stage) {
            case NO_HOME, CANDIDATE -> "settlement_register(core_house)";
            case SHELTERED -> "bind_respawn_at_bed";
            case RESPAWN_BOUND -> "scan_reserve_containers";
            case STOCKED -> "verify_lighting_and_protection";
            default -> null;
        };
        if (blocking != null && blocking.startsWith("reserve_short")) {
            next = "stock_reserve_box";          // 已读到但不够 → 去补
        } else if ("reserve_missing".equals(blocking)) {
            next = "stock_reserve_box";
        } else if ("reserve_unobserved".equals(blocking)) {
            next = "scan_reserve_containers";    // 真没读到 → 去扫
        }
        List<String> lines = new java.util.ArrayList<>(HomeSummary.lines(stage,
                String.valueOf(v.getOrDefault("label", base.assetId())),
                dimension, pos, asOf,
                observedItems ? reserve : null, asOf,
                blocking, next, nowMillis));
        // 2026-11-01：把「箱子里缺什么」和「背包里有什么」合起来给一句可执行建议——
        // 背包里已经有的，下一步是放进箱子，而不是再去做一遍（实机：它背着一包东西，基地箱是空的）。
        if (observedItems && !reserveSufficient) {
            String action = com.dwinovo.numen.rdd.policy.ReserveAction.describe(
                    com.dwinovo.numen.rdd.policy.ReserveAction.plan(shortfall, inventory));
            if (!action.isBlank()) lines.add("reserve_next=" + action);
        }
        return HomeSummary.block(lines);
    }

    /** 当前基地阶段（167 B3 的派生口径；没有基地观测就是 NO_HOME）。 */
    static HomeStage stageOf(AssetRegistry registry) {
        AssetRegistry.AssetEntry base = freshestBase(registry);
        if (base == null) return HomeStage.NO_HOME;
        Map<String, Object> v = base.observation().value();
        boolean houseUsable = v.get("facilities") instanceof List<?> l && !l.isEmpty();
        Map<String, Integer> reserve = new LinkedHashMap<>();
        if (v.get("important_items") instanceof Map<?, ?> m) {
            for (var e : m.entrySet()) {
                if (e.getValue() instanceof Number n) reserve.put(String.valueOf(e.getKey()), n.intValue());
            }
        }
        boolean reserveKnown = v.get("important_items") instanceof Map<?, ?>;
        return HomeStage.of(true, houseUsable, true, reserveKnown,
                HomeStage.reserveSufficient(reserveKnown ? reserve : null), false);
    }

    /** 基地备用箱是否**真的观测过**（有 world_base 且带 important_items）。 */
    static boolean reserveKnown(AssetRegistry registry) {
        AssetRegistry.AssetEntry base = freshestBase(registry);
        return base != null && base.observation().value().get("important_items") instanceof Map<?, ?>;
    }

    /** 基地备用相对 ReserveKit 的缺口；未知时返回空表（未知不等于缺失）。 */
    static java.util.List<String> reserveShortfall(AssetRegistry registry) {
        AssetRegistry.AssetEntry base = freshestBase(registry);
        if (base == null) return java.util.List.of();
        if (!(base.observation().value().get("important_items") instanceof Map<?, ?> m)) {
            return java.util.List.of();
        }
        Map<String, Integer> reserve = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
            if (e.getValue() instanceof Number n) reserve.put(String.valueOf(e.getKey()), n.intValue());
        }
        return com.dwinovo.numen.rdd.policy.ReserveKit.shortfall(reserve);
    }

    /**
     * 取「当前这个家」的基地观测。
     *
     * <p>2026-11-01 实机查明：base 观测分布在 6 个坐标上（它每换一次床就多一个「基地」），
     * 原来按「最新一条」取，经常拿到别的床旁边那个空箱子 → 上下文一直显示没有备用，
     * 而 1554 条观测里其实有铁镐。改成优先按**当前重生点**挑（{@link BaseSelector}）。
     */
    private static AssetRegistry.AssetEntry freshestBase(AssetRegistry registry) {
        if (registry == null) return null;
        java.util.List<AssetRegistry.AssetEntry> bases = new java.util.ArrayList<>();
        for (var e : RddAssetContext.worldAssets(registry)) {
            if (!"base".equals(String.valueOf(e.observation().value().get("kind")))) continue;
            bases.add(e);
        }
        if (bases.isEmpty()) return null;
        java.util.List<com.dwinovo.numen.rdd.core.BaseSelector.Candidate> cands = new java.util.ArrayList<>();
        for (var e : bases) {
            var v = e.observation().value();
            cands.add(new com.dwinovo.numen.rdd.core.BaseSelector.Candidate(
                    num(v.get("x")), num(v.get("y")), num(v.get("z")), e.observation().observedAt()));
        }
        int idx = com.dwinovo.numen.rdd.core.BaseSelector.pick(cands, RESPX.get(), RESPY.get(), RESPZ.get());
        return idx < 0 ? null : bases.get(idx);
    }

    /** 当前重生点（由插件在上下文构建时写入；未绑定为 null）。 */
    static final ThreadLocal<Integer> RESPX = new ThreadLocal<>();
    static final ThreadLocal<Integer> RESPY = new ThreadLocal<>();
    static final ThreadLocal<Integer> RESPZ = new ThreadLocal<>();

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
