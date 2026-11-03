package com.dwinovo.numen.plugins.learner.core;

import java.util.List;
import java.util.Locale;

/**
 * 近重复门：写经验之前，先跟库里已有条目比一次相似度（2026-11-03，主人有条件批）。
 *
 * <h2>为什么</h2>
 * 实测库从 19 条涨到 24 条时，同机制重复从 2 组变成 4 组：
 * <ul>
 *   <li>{@code mine 只寻路不开路} —— **4 条**（#8 #12 #20 #23）</li>
 *   <li>{@code interact_at 射线被挡} —— **4 条**（#10 #11 #13 #21）</li>
 *   <li>{@code 夜间露天刷怪} 2 条、{@code 女巫魔法} 2 条</li>
 * </ul>
 * 24 条实际只有 16 个机制（重复率约 33%）。库越大，检索排序越被同类稀释。
 *
 * <h2>判据（#653 文档里的阈值，用现有库实测标定）</h2>
 * 文本 = {@code title + " " + mechanism}，转小写、去空白标点，特征 = 字符二元组集合，
 * 相似度 = Jaccard。实测同机制最低 **0.265**、非同机制最高 **0.202**：
 * <ul>
 *   <li>{@code >= 0.30} → 判重复（建议 REVISE，并指向已存在的条目）；</li>
 *   <li>{@code 0.24 ~ 0.30} → 只标 {@code possible_duplicate_of}，不合并；</li>
 *   <li>{@code < 0.24} → 放行。</li>
 * </ul>
 *
 * <p><b>不改门禁语义</b>：这里只产出“是否像重复”的判定与依据，怎么处置（降级为 REVISE /
 * 只打标记 / 放行）由调用方决定。
 */
public final class NearDuplicateGate {

    private NearDuplicateGate() {}

    /** 判重复的阈值（实测同机制最低 0.265）。 */
    public static final double DUPLICATE_AT = 0.30;
    /** 只标记“疑似重复”的下限（实测非同机制最高 0.202）。 */
    public static final double SUSPICIOUS_AT = 0.24;

    /** 判定结果。 */
    public record Decision(double similarity, int matchedIndex, String matchedTitle,
                           boolean duplicate, boolean suspicious) {}

    /** 两个文本的字符二元组 Jaccard 相似度（中文友好，不依赖分词）。 */
    public static double similarity(String a, String b) {
        var x = bigrams(a);
        var y = bigrams(b);
        if (x.isEmpty() || y.isEmpty()) return 0.0;
        int inter = 0;
        for (String token : x) if (y.contains(token)) inter++;
        return (double) inter / (x.size() + y.size() - inter);
    }

    /**
     * 跟已有条目比一遍，取最相似的那条。
     *
     * @param title      新条目标题
     * @param mechanism  新条目机制
     * @param existing   库里已有条目的文本（调用方拼好 title+mechanism 即可）
     * @param titles     与 existing 一一对应的标题（用于回执里告诉人“撞的是哪条”）
     */
    public static Decision check(String title, String mechanism,
                                 List<String> existing, List<String> titles) {
        String text = (title == null ? "" : title) + " " + (mechanism == null ? "" : mechanism);
        double best = 0.0;
        int bestIndex = -1;
        if (existing != null) {
            for (int i = 0; i < existing.size(); i++) {
                double sim = similarity(text, existing.get(i));
                if (sim > best) {
                    best = sim;
                    bestIndex = i;
                }
            }
        }
        String matchedTitle = (titles != null && bestIndex >= 0 && bestIndex < titles.size())
                ? titles.get(bestIndex) : "";
        return new Decision(best, bestIndex, matchedTitle,
                best >= DUPLICATE_AT, best >= SUSPICIOUS_AT && best < DUPLICATE_AT);
    }

    private static java.util.Set<String> bigrams(String raw) {
        if (raw == null) return java.util.Set.of();
        String t = raw.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}]+", "");
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int i = 0; i + 1 < t.length(); i++) out.add(t.substring(i, i + 2));
        return out;
    }
}
