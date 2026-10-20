package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品<b>转化检测</b>（G1c-2，75号 §3.2「原木→木板、矿石→锭…不因背包减少就报资产丢失」）。
 *
 * <p><b>为什么需要它</b>：需求检测只看持有量。她砍了 4 原木、合成了 16 木板后，
 * 原木数量下降会被当成「缺口扩大」——而实际上东西没丢，只是换了形态。
 * 本类从「两次背包快照的差额」里识别出这种转化，产出证据（{@code asset_transform} 事件），
 * <b>不参与判定</b>（判定层读它是后续批次的事）。
 *
 * <p><b>保守口径（刻意的）</b>：
 * <ul>
 *   <li>必须<b>输入真的减少 + 输出真的增加</b>同时成立才算转化；
 *       只增不减（比如捡到木板）不算——那可能是拾取，不是合成。</li>
 *   <li>按配方比例取整数倍：{@code n = min(输入减少/配方输入, 输出增加/配方输出)}，
 *       {@code n >= 1} 才报。半份不报（不吃掉证据也不编造）。</li>
 *   <li>炉烧（矿石→锭）与合成（原木→木板）不区分「设备」，只认物品形态变化——
 *       本类不是配方账本，是<b>证据</b>。</li>
 * </ul>
 *
 * <p>纯 JVM、无 MC 依赖、可单测。
 */
public final class TransformationDetector {

    /** 一次识别出的转化。{@code inputs}/{@code outputs} 是「物品键 → 数量」。 */
    public record Transform(String kind, Map<String, Integer> inputs, Map<String, Integer> outputs) {
        /** 事件用的一行短文本（有界，不塞大表）。 */
        public String describe() {
            return kind + ": " + join(inputs) + " → " + join(outputs);
        }

        private static String join(Map<String, Integer> m) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Integer> e : m.entrySet()) {
                if (sb.length() > 0) {
                    sb.append("+");
                }
                sb.append(e.getKey()).append("×").append(e.getValue());
            }
            return sb.toString();
        }
    }

    /** 单条保守配方：输入键/数量 → 输出键/数量。 */
    private record Rule(String input, int inputQty, String output, int outputQty, String kind) {}

    private static final List<Rule> RULES = buildRules();

    private static List<Rule> buildRules() {
        List<Rule> rules = new ArrayList<>();
        for (String wood : List.of("oak", "birch", "spruce", "jungle", "acacia", "dark_oak", "mangrove", "cherry")) {
            rules.add(new Rule("minecraft:" + wood + "_log", 1,
                    "minecraft:" + wood + "_planks", 4, "log→planks"));
        }
        for (String wood : List.of("oak", "birch", "spruce", "jungle", "acacia", "dark_oak", "mangrove", "cherry")) {
            rules.add(new Rule("minecraft:" + wood + "_planks", 2,
                    "minecraft:stick", 4, "planks→sticks"));
        }
        rules.add(new Rule("minecraft:cobblestone", 1, "minecraft:stone", 1, "smelt:cobblestone→stone"));
        rules.add(new Rule("minecraft:sand", 1, "minecraft:glass", 1, "smelt:sand→glass"));
        rules.add(new Rule("minecraft:raw_iron", 1, "minecraft:iron_ingot", 1, "smelt:raw_iron→iron_ingot"));
        rules.add(new Rule("minecraft:raw_copper", 1, "minecraft:copper_ingot", 1, "smelt:raw_copper→copper_ingot"));
        rules.add(new Rule("minecraft:raw_gold", 1, "minecraft:gold_ingot", 1, "smelt:raw_gold→gold_ingot"));
        rules.add(new Rule("minecraft:clay_ball", 1, "minecraft:brick", 1, "smelt:clay_ball→brick"));
        return List.copyOf(rules);
    }

    private TransformationDetector() {}

    /**
     * 从两次背包快照的差额识别转化。
     *
     * @param previous 上一次快照（缺 key = 0）；null/空 → 无法比较，返回空表
     * @param current  这一次快照
     */
    public static List<Transform> detect(Map<String, Integer> previous, Map<String, Integer> current) {
        if (previous == null || previous.isEmpty() || current == null) {
            return List.of();
        }
        List<Transform> out = new ArrayList<>();
        for (Rule rule : RULES) {
            int consumed = delta(previous, current, rule.input());      // 正数 = 减少了
            int produced = -delta(previous, current, rule.output());    // 正数 = 增加了
            if (consumed < rule.inputQty() || produced < rule.outputQty()) {
                continue;
            }
            int n = Math.min(consumed / rule.inputQty(), produced / rule.outputQty());
            if (n < 1) {
                continue;
            }
            Map<String, Integer> inputs = new LinkedHashMap<>();
            inputs.put(rule.input(), n * rule.inputQty());
            Map<String, Integer> outputs = new LinkedHashMap<>();
            outputs.put(rule.output(), n * rule.outputQty());
            out.add(new Transform(rule.kind(), Map.copyOf(inputs), Map.copyOf(outputs)));
        }
        return List.copyOf(out);
    }

    /** {@code previous - current}：正数 = 这次少了多少。 */
    private static int delta(Map<String, Integer> previous, Map<String, Integer> current, String key) {
        int p = Math.max(0, previous.getOrDefault(key, 0));
        int c = Math.max(0, current.getOrDefault(key, 0));
        return p - c;
    }
}
