package com.dwinovo.numen.rdd.policy;

import java.util.List;
import java.util.Map;

/**
 * 基地备用箱观测的**一次性投影**（TC-RESERVE-01，2026-11-04）。
 *
 * <p>为什么要有它：同一份 {@code world_base} 观测曾被两条路径各算一遍"还能不能用"——
 * 展示走 {@link HomeSummary#fresh}，准入只看 {@code important_items} 这个 Map 存不存在。
 * 结果就是「摘要说读数过期、不知道还有没有」，而风险门仍按旧库存放行。
 * 现在 {@code known/items/asOf} 只有一套口径：{@link #of} 里算一次，
 * 摘要、阶段、缺口、风险门全部读它。
 *
 * <p>三种"不可用"必须分开说清，但结论一致（{@code known()==false}）：
 * <ul><li>压根没观测到（{@link #unobserved()}）；</li>
 * <li>观测过但超过 TTL（读数过期）；</li>
 * <li>时间戳在 now 之后（时钟/存档脏数据，同样不可信）。</li></ul>
 * 过期不等于要删数据：{@link #rawItems()} 保留原始读数，{@link #items()} 才归 null。
 */
public final class HomeReserve {

    /** 原始读数；null 表示"这条观测里没有可读库存"（≠ 空箱子）。 */
    private final Map<String, Integer> observed;
    private final Long asOf;
    private final boolean known;

    private HomeReserve(Map<String, Integer> observed, Long asOf, boolean known) {
        this.observed = observed;
        this.asOf = asOf;
        this.known = known;
    }

    /** 没读到 important_items（或压根没有基地）。 */
    public static HomeReserve unobserved() {
        return new HomeReserve(null, null, false);
    }

    /**
     * 由一条基地观测派生。
     *
     * @param observedItems 观测里的 important_items；null 表示字段不存在（=没观测到），空 Map 表示"看过了，是空的"
     * @param observedAt    观测时刻；null 视为未知
     * @param nowMillis     本次判定用的**唯一** now（同一轮里展示与合规必须传同一个值）
     */
    public static HomeReserve of(Map<String, Integer> observedItems, Long observedAt, long nowMillis) {
        if (observedItems == null) return unobserved();
        return new HomeReserve(observedItems, observedAt, HomeSummary.fresh(observedAt, nowMillis));
    }

    /** 这份读数现在能不能当证据用：新鲜且时间合法。 */
    public boolean known() {
        return known;
    }

    /** 可用的备用读数；未知时返回 null——**不要**退化成空 Map，那会把"不知道"写成"没有"。 */
    public Map<String, Integer> items() {
        return known ? observed : null;
    }

    /** 原始读数，不论新鲜与否：诊断/留证用，别拿它做准入。 */
    public Map<String, Integer> rawItems() {
        return observed;
    }

    /** 观测时刻（原始；不会因为过期而抹掉）。 */
    public Long asOf() {
        return asOf;
    }

    /** 达没达最低清单；未知一律不算达标。 */
    public boolean sufficient() {
        return known && ReserveKit.sufficient(observed);
    }

    /** 缺口列表；未知时返回空表（沿用旧协议：空表≠达标，必须同时看 {@link #known()}）。 */
    public List<String> shortfall() {
        return known ? ReserveKit.shortfall(observed) : List.of();
    }

    /** 展示层裁剪（例如只列 {@code RESERVE_KEYS}）：known/asOf 不变，只换条目。 */
    public HomeReserve withItems(Map<String, Integer> subset) {
        if (observed == null) return this;
        return new HomeReserve(subset == null ? Map.of() : subset, asOf, known);
    }

    @Override
    public String toString() {
        return "HomeReserve{known=" + known + ", asOf=" + asOf
                + ", items=" + (known ? String.valueOf(observed) : "(过期或未观测)") + "}";
    }
}
