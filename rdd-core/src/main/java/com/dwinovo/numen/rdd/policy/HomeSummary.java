package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 执行请求里的「基地摘要」四行（167 §B3）。
 *
 * <p>为什么要单独成类：原来的基地事实（{@code world_base}）**写得很厚、消费得很薄**——
 * {@code complete/facilities/important_items} 只被用来排序或兜底，真正进上下文的只有一句 summary。
 * 这里把「阶段 + 硬缺口 + 下一步」压成固定几行，让执行 AI 每次请求都能看到。
 *
 * <p>三条硬规则（167 §B3）：
 * <ul><li>UNKNOWN 不写成 0；</li>
 * <li>过期观测不算满足备用门（{@code staleAfterMillis}）；</li>
 * <li>只陈述事实与下一步，不把 settlement 工具正文塞进来。</li></ul>
 */
public final class HomeSummary {

    private HomeSummary() {}

    /** 备用观测超过这个时间就当成不新鲜（默认 10 分钟，与容器缓存的刷新节奏同量级）。 */
    public static final long DEFAULT_STALE_AFTER_MS = 10 * 60 * 1000L;

    /**
     * @param stage        由事实算出的阶段
     * @param facilityId   基地设施 id（可为 null）
     * @param respawnDim   重生维度（未绑定为 null）
     * @param respawnPos   重生坐标（未绑定为 null）
     * @param respawnAsOf  绑定点观测时刻（毫秒，未知为 null）
     * @param reserve      基站备用清单（未知为 null / 空 map 表示"已知但空"）
     * @param reserveAsOf  备用扫描时刻（毫秒，未知为 null）
     * @param blocking     当前硬缺口（可为 null）
     * @param next         下一步（可为 null）
     * @param nowMillis    当前时刻，用于判断过期
     */
    public static List<String> lines(HomeStage stage, String facilityId,
                                     String respawnDim, String respawnPos, Long respawnAsOf,
                                     Map<String, Integer> reserve, Long reserveAsOf,
                                     String blocking, String next, long nowMillis) {
        // TC-RESERVE-01（2026-11-04）：Map + asOf 这种"散装"入参一律先折成 {@link HomeReserve}，
        // 新鲜度只在它里面算一次；这里不再自己写 age 公式，免得未来时间戳出现"渲染新鲜、判定过期"。
        return lines(stage, facilityId, respawnDim, respawnPos, respawnAsOf,
                HomeReserve.of(reserve, reserveAsOf, nowMillis), blocking, next, nowMillis);
    }

    /** 同一份摘要，改用已经算好新鲜度的备用投影（展示与准入共用一把尺子）。 */
    public static List<String> lines(HomeStage stage, String facilityId,
                                     String respawnDim, String respawnPos, Long respawnAsOf,
                                     HomeReserve reserve, String blocking, String next, long nowMillis) {
        HomeReserve r = reserve == null ? HomeReserve.unobserved() : reserve;
        List<String> out = new ArrayList<>(6);
        HomeStage st = stage == null ? HomeStage.NO_HOME : stage;
        out.add("home_stage=" + st.name());
        out.add("home_facility=" + (facilityId == null || facilityId.isBlank() ? "UNKNOWN" : facilityId));

        if (respawnDim == null || respawnPos == null) {
            out.add("respawn=UNBOUND");
        } else {
            out.add("respawn=BOUND " + respawnDim + " " + respawnPos
                    + (respawnAsOf == null ? " asOf=UNKNOWN" : " asOf=" + ago(respawnAsOf, nowMillis)));
        }

        // asOf 只在「这条观测真的可用」时才有意义：UNKNOWN 时后面跟个 asOf 会让人以为
        // "这是 32 分钟前的可靠读数"，而实际含义是"没有可用读数"。实机 167 B3 首跑就出现了这种歧义。
        out.add(HomeStage.describe(r.items(), r.known())
                + (r.known() && r.asOf() != null ? " asOf=" + ago(r.asOf(), nowMillis) : ""));

        if (blocking != null && !blocking.isBlank()) out.add("blocking=" + blocking);
        if (next != null && !next.isBlank()) out.add("next=" + next);
        return List.copyOf(out);
    }

    /**
     * 一条观测是否还新鲜（供调用方与这里的渲染用**同一个**判据）。
     *
     * <p>2026-11-01 实机抓到的不一致：blocking=reserve_short:cooked_beef 0/8 与 reserve=UNKNOWN 同时出现——
     * 因为渲染层按新鲜度降级成 UNKNOWN，而 blocking 仍按「观测过」算缺口。两处必须同一把尺子。
     */
    public static boolean fresh(Long observedAtMillis, long nowMillis) {
        if (observedAtMillis == null) return false;
        long age = nowMillis - observedAtMillis;
        return age >= 0 && age <= staleAfter();
    }

    private static long staleAfter() {
        return Long.getLong("numen.home.reserve.staleMs", DEFAULT_STALE_AFTER_MS);
    }

    /** {@code 12s} / {@code 3m} / {@code 2h}：只给量级，不给绝对时间戳（省 token 且够用）。 */
    static String ago(long atMillis, long nowMillis) {
        long d = Math.max(0, nowMillis - atMillis) / 1000;
        if (d < 60) return d + "s";
        if (d < 3600) return (d / 60) + "m";
        return (d / 3600) + "h";
    }

    /** 以 XML 风格包一段，便于和其它事实块共存。 */
    public static String block(List<String> lines) {
        if (lines == null || lines.isEmpty()) return "";
        return "<home>\n" + String.join("\n", lines) + "\n</home>";
    }
}
