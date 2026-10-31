package com.dwinovo.numen.plugins.learner.contract;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 同一样本「内部复盘 vs 外部复盘」的对账（158 §1、159 F）。
 *
 * <h2>它输出的是"分歧"，不是"谁对"</h2>
 * 158 §1 原文：归因结论出现实质冲突时，样本标记 `ATTRIBUTION_CONFLICT` 并提升复核优先级；
 * 分歧率作为系统健康指标，用来发现<b>材料缺失、模板偏差或环境块问题</b>。
 *
 * <p>所以这里不判对错，只判"两边的结论差多远"。判对错需要人，
 * 而机器能做的是把差异摆出来并给出一个可统计的数。
 *
 * <h2>三种关系</h2>
 * <ul>
 *   <li>{@link Relation#AGREE} —— 归一化后完全一致；</li>
 *   <li>{@link Relation#SUBSET} —— 一方是另一方的子集（多说了几条，不是矛盾）；</li>
 *   <li>{@link Relation#CONFLICT} —— 存在互相排斥的结论。</li>
 * </ul>
 * 把 SUBSET 当成 CONFLICT，会让分歧率虚高、复核队列被"说得更细的那一方"灌满。
 *
 * <p>纯 JVM，可离线单测。
 */
public final class AttributionReconcile {

    /** 结论集一侧。 */
    public record Side(Submission.Mode mode, List<String> conclusions) {
        public Side {
            conclusions = conclusions == null ? List.of() : List.copyOf(conclusions);
        }

        Set<String> normalized() {
            Set<String> out = new LinkedHashSet<>();
            for (String c : conclusions) {
                String n = norm(c);
                if (!n.isEmpty()) {
                    out.add(n);
                }
            }
            return out;
        }
    }

    /** 关系。 */
    public enum Relation {
        AGREE,
        SUBSET,
        CONFLICT
    }

    /**
     * 对账结果。
     *
     * @param divergence 分歧度 = 1 - Jaccard；AGREE 时为 0，SUBSET 时为 0
     * @param onlyInternal 内部独有（外部没提）
     * @param onlyExternal 外部独有
     */
    public record Result(Relation relation, double divergence,
                         List<String> onlyInternal, List<String> onlyExternal) {
        public Result {
            onlyInternal = onlyInternal == null ? List.of() : List.copyOf(onlyInternal);
            onlyExternal = onlyExternal == null ? List.of() : List.copyOf(onlyExternal);
        }

        public boolean conflict() {
            return relation == Relation.CONFLICT;
        }
    }

    private AttributionReconcile() {
    }

    public static Result reconcile(Side internal, Side external) {
        Set<String> a = internal == null ? Set.of() : internal.normalized();
        Set<String> b = external == null ? Set.of() : external.normalized();

        if (a.isEmpty() && b.isEmpty()) {
            return new Result(Relation.AGREE, 0.0, List.of(), List.of());
        }
        if (a.equals(b)) {
            return new Result(Relation.AGREE, 0.0, List.of(), List.of());
        }

        Set<String> onlyA = new LinkedHashSet<>(a);
        onlyA.removeAll(b);
        Set<String> onlyB = new LinkedHashSet<>(b);
        onlyB.removeAll(a);

        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        Set<String> inter = new LinkedHashSet<>(a);
        inter.retainAll(b);
        double jaccard = union.isEmpty() ? 1.0 : (double) inter.size() / union.size();

        // 一方完全被另一方包含：多说的那几条不算矛盾。
        if (onlyA.isEmpty() || onlyB.isEmpty()) {
            return new Result(Relation.SUBSET, 0.0, List.copyOf(onlyA), List.copyOf(onlyB));
        }
        return new Result(Relation.CONFLICT, 1.0 - jaccard, List.copyOf(onlyA), List.copyOf(onlyB));
    }

    private static String norm(String raw) {
        if (raw == null) {
            return "";
        }
        String s = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC);
        return s.trim().replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT);
    }
}
