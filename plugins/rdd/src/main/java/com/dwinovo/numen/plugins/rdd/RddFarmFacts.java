package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.policy.FarmProduction;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 农田产出证据（167 §B7）：只用**背包里可食作物的净增**判断，不新增采集。
 *
 * <p>为什么必须单独有一条：settlement 的 farm_basic 目前是 STRUCTURE_ONLY，
 * "建好了"会被读成"有粮了"。而 167 §2.3 的红线是：农田存在 ≠ 已有食物产出。
 *
 * <p>为什么用背包净增：它同时排除了"只建了结构"和"只是把存粮挪了一遍"两种情况——
 * 只有真的多出来，才算田里长出了东西。代价是：吃了/放进箱子会抵消，这一点写在
 * {@code FarmProduction} 的注释里，不假装它是精确产量。
 */
final class RddFarmFacts {

    private RddFarmFacts() {}

    private static final Map<UUID, FarmProduction.Sample> LAST = new ConcurrentHashMap<>();
    /** 每个同伴只在"首次观测到产出"时发一次事件，避免刷屏。 */
    private static final Map<UUID, Boolean> ANNOUNCED = new ConcurrentHashMap<>();

    /** 每次拿到背包快照时调一次；返回本次判定。 */
    static FarmProduction.State update(UUID companionId, Map<String, Integer> inventory, long nowMillis) {
        if (companionId == null) return FarmProduction.State.UNKNOWN;
        FarmProduction.Sample current =
                new FarmProduction.Sample(nowMillis, FarmProduction.cropCounts(inventory));
        FarmProduction.Sample previous = LAST.put(companionId, current);
        FarmProduction.State state = FarmProduction.evaluate(previous, current);
        if (state == FarmProduction.State.PRODUCING && ANNOUNCED.putIfAbsent(companionId, true) == null) {
            try {
                RddMonitor.publish("farm_production_observed", Map.of(
                        "companionId", companionId.toString(),
                        "crops", current.counts().toString(),
                        "previous", previous == null ? "none" : previous.counts().toString(),
                        "note", "背包里可食作物净增 ≥1：田里确实产出了东西（不是只有结构）"));
            } catch (RuntimeException ignored) {
                // 观测失败不影响主链路
            }
        }
        return state;
    }

    /** 上下文里的一行（没有两次采样时为 UNKNOWN，不写成 0）。 */
    static String lineFor(UUID companionId, Map<String, Integer> inventory, long nowMillis) {
        FarmProduction.State state = update(companionId, inventory, nowMillis);
        FarmProduction.Sample latest = LAST.get(companionId);
        return FarmProduction.describe(state, latest, nowMillis);
    }
}
