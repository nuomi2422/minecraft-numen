package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.rdd.policy.HomeReserve;
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
 *
 * <p>2026-11-04（TC-RESERVE-01）：展示与准入改为共用同一份 {@link HomeView}。
 * 原来 {@code blockFor} 按新鲜度降级、{@code stageOf/reserveKnown/reserveShortfall} 却只看
 * {@code important_items} 是否存在，于是"摘要说读数过期"与"风险门按旧库存放行"能同时成立。
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
        // TC-RESERVE-01：选家与新鲜度只做一次，下面每一行都从这一份视图派生。
        HomeView view = HomeView.of(registry, nowMillis);
        if (view.base() == null) {
            // 没观测到基地不等于没有基地：只报 NO_HOME，并明确 reserve 未知（不写 0）。
            return HomeSummary.block(HomeSummary.lines(HomeStage.NO_HOME, null, null, null, null,
                    HomeReserve.unobserved(), null, null, nowMillis));
        }
        Map<String, Object> v = view.value();
        long asOf = view.asOf();
        String dimension = String.valueOf(v.getOrDefault("dimension", "unknown"));
        String pos = v.get("x") + " " + v.get("y") + " " + v.get("z");

        HomeReserve reserve = view.reserve();
        // 展示裁剪：只列 RESERVE_KEYS（167 B5 的省 token 口径）；缺口仍按完整读数算。
        Map<String, Integer> shownItems = reserve.rawItems() == null ? null : reserveKeysOf(reserve.rawItems());
        HomeReserve shown = reserve.withItems(shownItems);
        boolean reserveUsable = reserve.known();
        boolean reserveSufficient = reserve.sufficient();
        List<String> shortfall = reserve.shortfall();
        boolean litProtected = false;   // 保护/光照判据尚未产生事实，明确保持 false 而不是假装通过

        HomeStage stage = view.stage();
        String blocking;
        if (stage == HomeStage.ESTABLISHED) {
            blocking = null;
        } else if (!reserveUsable) {
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
                String.valueOf(v.getOrDefault("label", view.base().assetId())),
                dimension, pos, asOf,
                shown,
                blocking, next, nowMillis));
        // 2026-11-01：把「箱子里缺什么」和「背包里有什么」合起来给一句可执行建议——
        // 背包里已经有的，下一步是放进箱子，而不是再去做一遍（实机：它背着一包东西，基地箱是空的）。
        if (reserveUsable && !reserveSufficient) {
            String action = com.dwinovo.numen.rdd.policy.ReserveAction.describe(
                    com.dwinovo.numen.rdd.policy.ReserveAction.plan(shortfall, inventory));
            if (!action.isBlank()) lines.add("reserve_next=" + action);
            // 2026-11-01（167 §9.15）：家里缺的东西，可能还留在旧据点里。只报线索，标 LAZY。
            String stale = staleBaseLine(registry, v, asOf, nowMillis);
            if (!stale.isBlank()) lines.add(stale);
        }
        return HomeSummary.block(lines);
    }

    /** 当前基地阶段（167 B3 的派生口径；没有基地观测就是 NO_HOME）。 */
    static HomeStage stageOf(AssetRegistry registry) {
        return stageOf(registry, System.currentTimeMillis());
    }

    /** 显式 now 的版本：同一次判定只取一个 now，展示与准入必须传同一个值。 */
    static HomeStage stageOf(AssetRegistry registry, long nowMillis) {
        return HomeView.of(registry, nowMillis).stage();
    }

    /** 基地备用箱现在是否**可信可用**（有 world_base、带 important_items、且读数未过期）。 */
    static boolean reserveKnown(AssetRegistry registry) {
        return reserveKnown(registry, System.currentTimeMillis());
    }

    static boolean reserveKnown(AssetRegistry registry, long nowMillis) {
        return HomeView.of(registry, nowMillis).reserve().known();
    }

    /** 基地备用相对 ReserveKit 的缺口；未知时返回空表（未知不等于缺失，判定必须同时看 reserveKnown）。 */
    static List<String> reserveShortfall(AssetRegistry registry) {
        return reserveShortfall(registry, System.currentTimeMillis());
    }

    static List<String> reserveShortfall(AssetRegistry registry, long nowMillis) {
        return HomeView.of(registry, nowMillis).reserve().shortfall();
    }

    /**
     * 选中基地的一次性视图（TC-RESERVE-01）。
     *
     * <p>同一 now、同一次选家：摘要（{@link #blockFor}）、阶段、known、缺口全部从这里派生。
     * 原因是 TTL 边界上三个入口各自调用 {@code System.currentTimeMillis()} + 各自选一次家，
     * 能出现"这次读是新鲜的、那次读已经过期"的裂缝；现在裂缝只剩一处，而且不存在。
     */
    record HomeView(AssetRegistry.AssetEntry base, Map<String, Object> value, long asOf,
                    boolean houseUsable, HomeReserve reserve, HomeStage stage) {

        static HomeView of(AssetRegistry registry, long nowMillis) {
            AssetRegistry.AssetEntry base = freshestBase(registry);
            if (base == null) {
                return new HomeView(null, Map.of(), 0L, false, HomeReserve.unobserved(), HomeStage.NO_HOME);
            }
            Map<String, Object> value = base.observation().value();
            boolean houseUsable = value.get("facilities") instanceof List<?> l && !l.isEmpty();
            long asOf = base.observation().observedAt();
            HomeReserve reserve = HomeReserve.of(itemsOf(value.get("important_items")), asOf, nowMillis);
            return new HomeView(base, value, asOf, houseUsable, reserve,
                    HomeStage.of(true, houseUsable, true, reserve, false));
        }
    }

    /** {@code important_items} → 备用条目；字段不是 Map 就是"没观测到"（null），不能当成空 Map。 */
    private static Map<String, Integer> itemsOf(Object rawItems) {
        if (!(rawItems instanceof Map<?, ?> m)) return null;
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var e : m.entrySet()) {
            if (!(e.getValue() instanceof Number n)) continue;
            out.put(String.valueOf(e.getKey()), n.intValue());
        }
        return out;
    }

    /** 展示裁剪：只留 RESERVE_KEYS 里那几类。 */
    private static Map<String, Integer> reserveKeysOf(Map<String, Integer> items) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (var e : items.entrySet()) {
            if (RESERVE_KEYS.contains(e.getKey())) out.put(e.getKey(), e.getValue());
        }
        return out;
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

    /**
     * 「旧据点里还有你要的东西」——只看 base 观测里含 RESERVE_KEYS 的那些，排除当前家。
     * 全部标 LAZY：区块可能没加载、箱子可能早被搬空，模型必须重新确认。
     */
    private static String staleBaseLine(AssetRegistry registry, Map<String, Object> homeValue,
                                        long homeAsOf, long nowMillis) {
        try {
            int hx = num(homeValue.get("x"));
            int hy = num(homeValue.get("y"));
            int hz = num(homeValue.get("z"));
            java.util.List<com.dwinovo.numen.rdd.core.StaleBases.Candidate> all = new java.util.ArrayList<>();
            for (var e : RddAssetContext.worldAssets(registry)) {
                var v = e.observation().value();
                if (!"base".equals(String.valueOf(v.get("kind")))) continue;
                Map<String, Integer> items = new LinkedHashMap<>();
                if (v.get("important_items") instanceof Map<?, ?> m) {
                    for (var kv : m.entrySet()) {
                        if (kv.getValue() instanceof Number n) items.put(String.valueOf(kv.getKey()), n.intValue());
                    }
                }
                all.add(new com.dwinovo.numen.rdd.core.StaleBases.Candidate(
                        num(v.get("x")), num(v.get("y")), num(v.get("z")),
                        e.observation().observedAt(), items));
            }
            var hits = com.dwinovo.numen.rdd.core.StaleBases.find(all, hx, hy, hz, 8.0, RESERVE_KEYS, 1);
            if (hits.isEmpty()) return "";
            return com.dwinovo.numen.rdd.core.StaleBases.describe(hits.get(0), nowMillis, RESERVE_KEYS);
        } catch (RuntimeException ex) {
            return "";
        }
    }

    /** 当前重生点（由插件在上下文构建时写入；未绑定为 null）。 */
    static final ThreadLocal<Integer> RESPX = new ThreadLocal<>();
    static final ThreadLocal<Integer> RESPY = new ThreadLocal<>();
    static final ThreadLocal<Integer> RESPZ = new ThreadLocal<>();

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
