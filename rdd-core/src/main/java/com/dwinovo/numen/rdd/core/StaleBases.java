package com.dwinovo.numen.rdd.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 「别的据点里还有你的东西」（167 §9.15 的策略缺口，2026-11-01）。
 *
 * <p>实机：家锚定在新位置（358,65,636），但关键备用留在旧据点那片（371,7xx，
 * `iron_pickaxe` 出现 1554 次）。上下文只说「你家箱子缺铁镐」，没有任何地方说
 * 「你 371 那边还有一把」——于是它只能重新做一把。
 *
 * <p>本类只找**线索**：除当前家以外、含关键备用的旧基地观测。
 * 全部标 LAZY（要重新确认才能依赖），因为它可能早就被搬空或区块未加载。
 */
public final class StaleBases {

    private StaleBases() {}

    /** 一处旧基地观测。 */
    public record Candidate(int x, int y, int z, long observedAtMillis, Map<String, Integer> items) {}

    /**
     * @param all      所有 base 观测
     * @param homeX/Y/Z 当前家的坐标（同一处要排除）
     * @param homeRadius 判定「同一处」的半径
     * @param keys     关心的物品 id
     * @param limit    最多返回几处
     */
    public static List<Candidate> find(List<Candidate> all, int homeX, int homeY, int homeZ,
                                       double homeRadius, List<String> keys, int limit) {
        if (all == null || all.isEmpty() || keys == null || keys.isEmpty() || limit <= 0) return List.of();
        List<Candidate> hits = new ArrayList<>();
        for (Candidate c : all) {
            if (dist(c, homeX, homeY, homeZ) <= homeRadius) continue;   // 家自己不算「旧据点」
            int score = 0;
            for (String k : keys) {
                Integer v = c.items() == null ? null : c.items().get(k);
                if (v != null && v > 0) score += v;
            }
            if (score > 0) hits.add(c);
        }
        hits.sort((a, b) -> {
            int sa = score(a, keys), sb = score(b, keys);
            if (sa != sb) return Integer.compare(sb, sa);
            return Long.compare(b.observedAtMillis(), a.observedAtMillis());
        });
        return List.copyOf(hits.size() > limit ? hits.subList(0, limit) : hits);
    }

    private static int score(Candidate c, List<String> keys) {
        int s = 0;
        for (String k : keys) {
            Integer v = c.items() == null ? null : c.items().get(k);
            if (v != null && v > 0) s += v;
        }
        return s;
    }

    private static double dist(Candidate c, int x, int y, int z) {
        return Math.sqrt(Math.pow(c.x() - x, 2) + Math.pow(c.y() - y, 2) + Math.pow(c.z() - z, 2));
    }

    /** 一行渲染：坐标 + 多久以前 + 有哪些关键物品（标 LAZY）。 */
    public static String describe(Candidate c, long nowMillis, List<String> keys) {
        if (c == null) return "";
        long ageMin = Math.max(0, nowMillis - c.observedAtMillis()) / 60000;
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            Integer v = c.items() == null ? null : c.items().get(k);
            if (v == null || v <= 0) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(k.replace("minecraft:", "")).append('×').append(v);
        }
        return "stale_base=" + c.x() + "," + c.y() + "," + c.z() + " asOf=" + ageMin + "m"
                + " has " + sb + " (LAZY: 重新确认后才能依赖)";
    }
}