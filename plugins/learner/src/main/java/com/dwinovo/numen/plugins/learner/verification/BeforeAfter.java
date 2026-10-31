package com.dwinovo.numen.plugins.learner.verification;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 执行前后世界对比（159 I：自动动作的验证信号）。
 *
 * <h2>它只回答"变没变"，不回答"变好没有"</h2>
 * 「世界确实变了」与「这个自动动作有用」是两件事：前者是观测，后者是归因。
 * 把两件事合在一起，就会出现"因为血量从 3 变成了 5，所以撤退规则有效"这类
 * 忽略了"这期间它一直在跑回血"的错误结论。
 *
 * <h2>缺字段不算"没变化"</h2>
 * 场次字段少了一个（比如 after 里没有 {@code inventory}），
 * 正确的结论是 <b>UNKNOWN</b>，不是"背包没变"。
 * 这类混淆在数据里看不出来 —— 只有把 missing 单独列出来，读者才会发现比不了。
 *
 * <p>纯 JVM、无 IO，可离线单测。
 */
public final class BeforeAfter {

    /**
     * 一次对比。
     *
     * @param comparable 两边都有可用快照（否则其余字段无意义）
     * @param missing    只有一侧有的字段（比不了的部分）
     * @param changed    两侧都有且值不同
     * @param added      只在 after 出现的键
     * @param removed    只在 before 出现的键
     */
    public record Diff(boolean comparable, List<String> missing,
                       List<String> changed, List<String> added, List<String> removed) {
        public Diff {
            missing = missing == null ? List.of() : List.copyOf(missing);
            changed = changed == null ? List.of() : List.copyOf(changed);
            added = added == null ? List.of() : List.copyOf(added);
            removed = removed == null ? List.of() : List.copyOf(removed);
        }

        public boolean anyChange() {
            return !changed.isEmpty() || !added.isEmpty() || !removed.isEmpty();
        }

        public static Diff notComparable(String why) {
            return new Diff(false, List.of(why == null ? "unknown" : why), List.of(), List.of(), List.of());
        }
    }

    private BeforeAfter() {
    }

    /**
     * 对比两份快照。
     *
     * <p>{@code null} 或空快照 → {@link Diff#notComparable}，
     * 因为"没采到"与"没变化"必须分开。
     */
    public static Diff diff(Map<String, Object> before, Map<String, Object> after) {
        if (before == null || before.isEmpty()) {
            return Diff.notComparable("before_snapshot_missing");
        }
        if (after == null || after.isEmpty()) {
            return Diff.notComparable("after_snapshot_missing");
        }
        Set<String> missing = new LinkedHashSet<>();
        List<String> changed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();

        for (Map.Entry<String, Object> e : after.entrySet()) {
            if (!before.containsKey(e.getKey())) {
                added.add(e.getKey());
            } else if (!sameValue(before.get(e.getKey()), e.getValue())) {
                changed.add(e.getKey());
            }
        }
        for (String k : before.keySet()) {
            if (!after.containsKey(k)) {
                removed.add(k);
            }
        }
        // 一边有值一边缺：这不是"不同"，是"比不了"。
        for (Map.Entry<String, Object> e : before.entrySet()) {
            if (e.getValue() == null) {
                missing.add(e.getKey());
            }
        }
        for (Map.Entry<String, Object> e : after.entrySet()) {
            if (e.getValue() == null) {
                missing.add(e.getKey());
            }
        }
        return new Diff(true, List.copyOf(missing), changed, added, removed);
    }

    private static boolean sameValue(Object a, Object b) {
        if (a == null && b == null) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        // 数值型用 compare：JSON 解析出来可能是 Integer 也可能是 Double。
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }
}
