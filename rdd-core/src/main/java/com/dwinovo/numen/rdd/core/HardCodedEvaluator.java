package com.dwinovo.numen.rdd.core;

import java.util.Map;

/**
 * 硬编码完成条件的纯 JVM 判定：只回答「给一堆物品计数，condition 满足没有」。
 *
 * <p>condition 形状（与 {@code Subtask.hardCoded} 一致）：
 * <pre>{@code
 * { "asset_key": "minecraft:oak_log", "minimum": 5 }
 * }</pre>
 *
 * <p>判定是纯函数、无副作用、无 IO——真身读背包的工作在宿主侧（{@code RddDetector}），
 * 这一层只做「计数够了没」，好单测。
 */
public final class HardCodedEvaluator {

    private HardCodedEvaluator() {}

    /** 缺省阈值：condition 里没给 minimum 时按 1 计。 */
    public static final int DEFAULT_MINIMUM = 1;

    /** {@code mode} 缺省值 = 持有语义（与本轮改动前的字节行为完全一致）。 */
    public static final String MODE_HOLD = "hold";
    /** {@code mode} 显式取值：本次二级期间<b>新增</b>多少，而不是当前持有多少。 */
    public static final String MODE_ACQUIRE = "acquire";

    /**
     * @param condition 二级目标的硬编码条件（{@code asset_key} 必需）
     * @param counts    物品 ID → 数量（宿主从真实环境统计）
     * @return 计数达到/超过 minimum 才算满足；缺 key 或条件畸形返回 {@code false}
     */
    public static boolean matches(Map<String, Object> condition, Map<String, Integer> counts) {
        return matches(condition, counts, null);
    }

    /**
     * 带 baseline 的判定：{@code mode=acquire} 时按<b>增量</b>算。
     *
     * <p><b>为什么需要 acquire</b>（2026-09-30 实机假完成）：派"收集 wheat_seeds x10"时她背包
     * 天生就有 33 个，{@code hold} 判据当场秒过 —— 二级直接 COMPLETED，<b>她一件都没种</b>。
     * 持有语义对"现在手里够不够"是对的，对"这一步有没有真的干活"是错的。
     *
     * <p>{@code baseline} = 进入该二级那一刻的计数（{@link TaskChain} 捕获，见
     * {@code acquireBaselines}）。{@code mode=hold}（缺省）时<b>不读 baseline</b>，
     * 与两参重载逐字一致；{@code mode=acquire} 且 baseline 缺失 → <b>判 false</b>
     * （宁可"还没达成"，也不在没有基线时假装达成）。
     *
     * <p>{@code acquire} 算的是<b>净增量</b>（当前 − 基线），所以"边做边消耗"会抵消收益。
     * 这是刻意的：{@code hold} 管"现在手里够不够"（不消耗型目标），
     * {@code acquire} 管"这一步有没有真的弄到东西"（采集型目标）。两者职责不重叠。
     *
     * <p>未知 {@code mode} 值一律 {@code false}：不静默降级成 hold
     * （那会让拼错的键悄悄变成另一种语义，正是本轮要消灭的那类假完成）。
     */
    public static boolean matches(Map<String, Object> condition, Map<String, Integer> counts,
                                  Map<String, Integer> baseline) {
        if (condition == null || counts == null) {
            return false;
        }
        if (condition.containsKey("type") && !"inventory".equals(condition.get("type"))) return false;
        Object minimumObj = condition.get("minimum");
        if (minimumObj != null && (!(minimumObj instanceof Number n) || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != n.intValue())) return false;
        int minimum = minimumObj instanceof Number n ? n.intValue() : DEFAULT_MINIMUM;
        if (minimum < 0) return false;
        if (condition.containsKey("group")) {
            Object group = condition.get("group");
            return !condition.containsKey("asset_key") && InventoryGroups.known(group) && minimum > 0
                    && InventoryGroups.count((String) group, counts) >= minimum;
        }
        Object assetKey = condition.get("asset_key");
        if (!(assetKey instanceof String key) || key.isBlank()) return false;
        if (MODE_ACQUIRE.equals(modeOf(condition))) {
            if (baseline == null) {
                // 兜底（2026-10-02 实测事故）：缺基线时**不要**把子目标判成永远做不到。
                // acquire 的本意是「防她早就有了、算假完成」，但基线缺失时它挡的不只是假完成，
                // 连真完成一起挡 —— 东西真在背包里也判不出成功，然后被看门狗当卡死、逼模型重做。
                // 宁可直接按持有量判（最多放过一次「早就有了」），也不要让子目标不可完成。
                return holdSatisfied(key, minimum, counts);
            }
            int gained = counts.getOrDefault(key, 0) - baseline.getOrDefault(key, 0);
            return gained >= minimum;
        }
        if (!MODE_HOLD.equals(modeOf(condition))) return false; // 未知/畸形 mode：显式不满足
        return holdSatisfied(key, minimum, counts);
    }

    /**
     * {@code hold} 语义：背包/持有量达到 {@code minimum} 就算满足（硬指标，不猜）。
     *
     * <p>抽出来给两条路径共用：{@code mode=hold} 直接用它，
     * {@code mode=acquire} 但基线缺失时降级到它。
     */
    private static boolean holdSatisfied(String key, int minimum, Map<String, Integer> counts) {
        Integer count = counts.get(key);
        return count != null ? Math.max(0, count) >= minimum : minimum == 0;
    }

    /**
     * 取并校验 {@code mode}：缺省 → {@code hold}；只接受 {@code hold}/{@code acquire}。
     * 畸形值返回 {@code null}（调用方据此判"条件非法"，不要当 hold 处理）。
     */
    public static String modeOf(Map<String, Object> condition) {
        if (condition == null || !condition.containsKey("mode")) return MODE_HOLD;
        Object raw = condition.get("mode");
        if (raw == null) return MODE_HOLD;
        if (!(raw instanceof String s)) return null;
        String v = s.trim();
        if (v.isEmpty()) return MODE_HOLD;
        return (MODE_HOLD.equals(v) || MODE_ACQUIRE.equals(v)) ? v : null;
    }
}
