package com.dwinovo.numen.rdd.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 成就条件修复请求的**预算**（037/T3 第二轮，2026-11-04）。
 *
 * <p>为什么必须有预算：{@code INVALID} 意味着"这个条件本身写错了"，正确处理是去改条件/重规划；
 * 但每一 tick 都判 INVALID 就每 tick 都请求重规划，会把规划器打爆 —— 那只是把"无限重试动作"
 * 换成了"无限重试规划"。所以按"每个二级一次"的预算来，用尽后只报一次"预算用尽"，
 * 不无限悄悄重试（EV-01 的隐含要求）。
 *
 * <p><b>预算值从哪来</b>：本仓目前没有"条件修复预算"这类现存策略；现行重试/超时常量都按
 * "同一步骤有限次"处理。这里取 {@link #DEFAULT_MAX_ATTEMPTS} = 1（每二级一次修复请求），
 * 是**显式候选值**、写在本常量里并且被测试钉住，不是散落在调用点的魔法数。
 * 后续若接入总重试预算，应当把这个值接到总预算上，而不是再写一个数。
 */
public final class AdvancementRepairBudget {

    /** 每个二级允许请求修复的次数（候选值，见类注释）。 */
    public static final int DEFAULT_MAX_ATTEMPTS = 1;

    /** 记录表上限：链很长时不无限增长（超出淘汰最旧）。 */
    private static final int MAX_TRACKED = 256;

    /** 一次请求的结果。 */
    public enum Decision {
        /** 预算内，允许请求修复。 */
        REQUEST,
        /** 本二级的修复预算已用尽。 */
        EXHAUSTED
    }

    private final int maxAttempts;
    private final Map<String, Integer> used = new LinkedHashMap<>();

    public AdvancementRepairBudget() {
        this(DEFAULT_MAX_ATTEMPTS);
    }

    public AdvancementRepairBudget(int maxAttempts) {
        this.maxAttempts = maxAttempts > 0 ? maxAttempts : DEFAULT_MAX_ATTEMPTS;
    }

    /** 该二级还能不能再请求一次修复；能则计数 +1。 */
    public synchronized Decision take(String subtaskId) {
        if (subtaskId == null || subtaskId.isBlank()) {
            return Decision.EXHAUSTED;
        }
        int already = used.getOrDefault(subtaskId, 0);
        if (already >= maxAttempts) {
            return Decision.EXHAUSTED;
        }
        used.put(subtaskId, already + 1);
        while (used.size() > MAX_TRACKED) {
            used.remove(used.keySet().iterator().next());
        }
        return Decision.REQUEST;
    }

    /** 已用次数（观测/测试用）。 */
    public synchronized int used(String subtaskId) {
        return used.getOrDefault(subtaskId, 0);
    }

    /** 二级完成后清账，避免同 id 复用时误判"已用尽"。 */
    public synchronized void forget(String subtaskId) {
        if (subtaskId != null) {
            used.remove(subtaskId);
        }
    }

    public int maxAttempts() {
        return maxAttempts;
    }
}
