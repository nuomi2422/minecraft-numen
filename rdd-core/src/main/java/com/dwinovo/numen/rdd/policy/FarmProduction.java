package com.dwinovo.numen.rdd.policy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 农田是否**真的在产粮**（167 §B7、§2.3 红线）。
 *
 * <p>为什么需要它：settlement 的 {@code farm_basic} 现有成熟度是 {@code STRUCTURE_ONLY}——
 * 结构建好就算"完成"，于是"有农田"会被当成"有食物"。而 167 明确要求：
 * 农田存在不等于已有食物产出，**必须有真实产出证据**。
 *
 * <p>做法不新增采集：看**可食作物的背包计数变化**（wheat/carrot/potato/beetroot）。
 * 只要在一个窗口内净增 ≥1，就算观测到一次产出。这不是"农田产量统计"，
 * 而是"她确实从田里拿到了能吃的东西"——比结构验收强，比精确产量弱，且不需要读世界。
 *
 * <p>未知与零严格分开：没有两次采样 = UNKNOWN，不是"没产出"。
 */
public final class FarmProduction {

    private FarmProduction() {}

    /** 视为"田里产出"的作物。刻意不含西瓜/南瓜（它们常来自野外）。 */
    public static final List<String> CROPS = List.of(
            "minecraft:wheat", "minecraft:carrots", "minecraft:potato", "minecraft:beetroot");

    /** 两次采样之间至少有一次净增，才算"观测到产出"。 */
    public static final int MIN_GAIN = 1;

    public enum State {
        /** 采样不足，无法判断。 */
        UNKNOWN,
        /** 有采样但窗口内没有净增。 */
        NO_GAIN,
        /** 观测到净增：田里确实产出了可食物。 */
        PRODUCING
    }

    /** 一次采样。 */
    public record Sample(long atMillis, Map<String, Integer> counts) {}

    public static Map<String, Integer> cropCounts(Map<String, Integer> inventory) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (inventory == null) return out;
        for (String k : CROPS) {
            Integer v = inventory.get(k);
            if (v != null && v > 0) out.put(k, v);
        }
        return out;
    }

    private static int total(Map<String, Integer> counts) {
        if (counts == null) return 0;
        int sum = 0;
        for (String k : CROPS) {
            Integer v = counts.get(k);
            if (v != null) sum += v;
        }
        return sum;
    }

    /**
     * 用最近两次采样判断。
     *
     * @param previous 上一次采样（可为 null）
     * @param current  这一次采样（可为 null）
     */
    public static State evaluate(Sample previous, Sample current) {
        if (previous == null || current == null) return State.UNKNOWN;
        int gain = total(current.counts()) - total(previous.counts());
        return gain >= MIN_GAIN ? State.PRODUCING : State.NO_GAIN;
    }

    /** 供上下文渲染的一句话。 */
    public static String describe(State state, Sample latest, long nowMillis) {
        if (state == null || state == State.UNKNOWN) return "food=UNKNOWN";
        if (state == State.PRODUCING) return "food=PRODUCING（观测到作物净增）";
        String age = latest == null ? "" : " asOf=" + HomeSummary.ago(latest.atMillis(), nowMillis);
        return "food=NO_GAIN（有农田但窗口内没有净增）" + age;
    }
}
