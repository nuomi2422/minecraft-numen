package com.dwinovo.numen.plugins.rdd;

import java.util.Locale;
import java.util.UUID;

/**
 * 2026-11-02（death49–56 实机）：空手回死亡点捡装备 = 送死。
 *
 * <p>纯逻辑放这里（不依赖 NumenTool/Minecraft），工具侧只调用；这样单测不用拖整个工具类。
 */
final class RddDeathRecoveryGuard {

    private RddDeathRecoveryGuard() {}

    /** 无战斗装备（背包空或胸甲槽空）+ 上次死因危险 → 不允许「先回死亡点捡」。 */
    static boolean allowRecoverFirst(boolean noCombatGear, String lastCause) {
        if (!noCombatGear) return true;
        if (lastCause == null) return true;
        String c = lastCause.toLowerCase(Locale.ROOT);
        return !(c.contains("lava") || c.contains("onfire") || c.contains("fire")
                || c.contains("fall") || c.contains("mob"));
    }

    /**
     * 最新一次死亡是否与窗口内的上一次死亡挤在同一片（同点连死）。
     *
     * <p>2026-11-03 审核：原来这里自己扫 deathAt 列表、radius 硬编 24、无时间窗，
     * 与 RddDeathLedger 里 RL-16 的 repeatDeathWithin(180s, 8.0) 是两份口径
     * （20 格/10 秒前会一边判重复、一边判不重复）。现在统一委托给台账，只留一套判断与一组常量。
     */
    static boolean repeatedNearby(java.util.UUID companionId, long windowTicks, double radiusBlocks) {
        return com.dwinovo.numen.rdd.core.RddDeathLedger.latestDeathRepeatNearby(
                companionId, windowTicks, radiusBlocks);
    }

    /** 供台账留痕：为什么放行/拒绝（尤其 unknown_cause 不能静默放行）。 */
    static String recoverGuardReason(boolean noCombatGear, String lastCause) {
        if (!noCombatGear) return "geared";
        if (lastCause == null || lastCause.isBlank()) return "unknown_cause_allow";
        String c = lastCause.toLowerCase(Locale.ROOT);
        boolean hazard = c.contains("lava") || c.contains("onfire") || c.contains("fire")
                || c.contains("fall") || c.contains("mob");
        return hazard ? "hazard_cause_refused" : "non_hazard_cause_allow";
    }

    /** 台账里 seq 最大的那条的死因（读不到返回空串，不猜）。 */
    static String lastDeathCause(UUID companionId) {
        if (companionId == null) return "";
        try {
            com.dwinovo.numen.rdd.core.RddDeathLedger.Death last = null;
            for (com.dwinovo.numen.rdd.core.RddDeathLedger.Death d
                    : com.dwinovo.numen.rdd.core.RddDeathLedger.all(companionId)) {
                if (last == null || d.seq() > last.seq()) last = d;
            }
            return last == null || last.cause() == null ? "" : last.cause();
        } catch (RuntimeException ignored) {
            return "";
        }
    }
}