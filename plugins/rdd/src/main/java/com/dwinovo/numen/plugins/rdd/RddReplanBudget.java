package com.dwinovo.numen.plugins.rdd;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 重规划预算（职责簇 F）：每个（同伴|一级）的自动重规划次数上限，防止无限烧 LLM。
 *
 * <p>把 {@link RddPlugin#requestReplan} 里的预算判断 + 耗尽埋点 + 计数从编排逻辑中拆出，
 * 行为逐字不变（P4 重规划/双向协商的预算口径 RL-5 依赖本类）。
 *
 * <ul>
 *   <li>失败驱动按 (同伴|一级) 最多 {@link #MAX_REPLAN_PER_PRIMARY} 次；</li>
 *   <li>协商驱动（士兵 COUNTER/REJECT）用<b>独立</b>上限 {@link #MAX_NEGOTIATION_REPLANS_PER_PRIMARY}，
 *       不被失败重规划的预算一口回绝（V1 实测根因）。</li>
 *   <li>超限时发 {@code LOOP_DETECTED}/{@code RECOVERY_FAILED}(死亡后恢复再卡预算时) + {@code replan_exhausted} 事件，回落停车。</li>
 * </ul>
 *
 * <p>入口：{@link RddPlugin#requestReplan} 调用 {@link #tryConsume}；重绑/{@code clearReplanCounts} 清零。
 */
final class RddReplanBudget {
    private static final Logger LOG = LoggerFactory.getLogger(RddReplanBudget.class);

    /** P4 重规划预算：每（同伴|一级）最多自动重规划次数；超限回落停车，防无限烧 LLM。 */
    private static final int MAX_REPLAN_PER_PRIMARY = 3;
    /** 协商驱动改单的独立预算（与失败驱动分开；士兵的反馈不该被 REPLAN 预算回绝）。 */
    private static final int MAX_NEGOTIATION_REPLANS_PER_PRIMARY = 3;
    private static final Map<String, Integer> REPLAN_COUNTS = new ConcurrentHashMap<>();

    private RddReplanBudget() {
    }

    /** 清世界状态：清空全部预算计数；由 RddPlugin ServerStopped 时调用。 */
    static void clearWorldState() {
        REPLAN_COUNTS.clear();
    }

    /** 清某同伴的重规划预算计数（重绑/清任务时调用）。 */
    static void clearReplanCounts(UUID companionId) {
        if (companionId == null) return;
        String prefix = companionId + "|";
        REPLAN_COUNTS.keySet().removeIf(k -> k.startsWith(prefix));
    }

    /** 清除单个同伴的内存缓存（REMOVE 事件时调用）。 */
    static void remove(UUID companionId) {
        if (companionId == null) return;
        clearReplanCounts(companionId);
    }

    /**
     * 尝试消费一次重规划预算。
     *
     * @return true=批准可进行重规划（已计数）；false=预算耗尽，已发出 停车 事件，调用方应回落。
     */
    static boolean tryConsume(UUID companionId, String primaryId, boolean fromNegotiation) {
        String key = fromNegotiation
                ? companionId + "|" + primaryId + "|nego"
                : companionId + "|" + primaryId;
        int limit = fromNegotiation ? MAX_NEGOTIATION_REPLANS_PER_PRIMARY : MAX_REPLAN_PER_PRIMARY;
        int used = REPLAN_COUNTS.getOrDefault(key, 0);
        if (used >= limit) {
            long gt = RddInstrumentation.currentGameTimeTicks();
            Map<String, Object> loopData = new LinkedHashMap<>();
            loopData.put("companionId", companionId.toString());
            loopData.put("task", primaryId);
            loopData.put("reason", (fromNegotiation ? "negotiation" : "failure") + " replan budget exhausted (loop)");
            loopData.put("context", Map.of("attempts", used, "threshold", limit, "fromNegotiation", fromNegotiation));
            RddInstrumentation.publishAt(RddInstrumentation.LOOP_DETECTED, loopData, gt);
            if (RddInstrumentation.recentDeath(companionId, gt)) {
                Map<String, Object> recData = new LinkedHashMap<>(loopData);
                recData.put("reason", "recovery attempt after death stalled on replan budget; parking instead");
                RddInstrumentation.publishAt(RddInstrumentation.RECOVERY_FAILED, recData, gt);
            }
            RddMonitor.publish("replan_exhausted", Map.of(
                    "companionId", companionId.toString(), "primary", primaryId,
                    "attempts", used, "reason", "replan budget exhausted; park instead"));
            LOG.warn("[rdd] 重规划预算耗尽，回落停车 {}:{}", companionId, primaryId);
            return false;
        }
        REPLAN_COUNTS.merge(key, 1, Integer::sum);
        return true;
    }
}