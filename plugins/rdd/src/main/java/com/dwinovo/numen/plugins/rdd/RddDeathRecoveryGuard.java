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

    /** 最近几次死亡是否集中在同一小片区域（同点连死）；解析不了的位置跳过，不猜。 */
    static boolean repeatedNearby(java.util.List<String> deathAt, int radius) {
        if (deathAt == null || deathAt.size() < 2) return false;
        double[] latest = parse(deathAt.get(deathAt.size() - 1));
        if (latest == null) return false;
        int from = Math.max(0, deathAt.size() - 3);
        for (int i = deathAt.size() - 2; i >= from; i--) {
            double[] p = parse(deathAt.get(i));
            if (p == null) continue;
            double dx = p[0] - latest[0], dy = p[1] - latest[1], dz = p[2] - latest[2];
            if (dx * dx + dy * dy + dz * dz <= (double) radius * radius) return true;
        }
        return false;
    }

    private static double[] parse(String s) {
        if (s == null) return null;
        String[] parts = s.split(",");
        if (parts.length < 3) return null;
        try {
            return new double[]{Double.parseDouble(parts[0].trim()),
                    Double.parseDouble(parts[1].trim()), Double.parseDouble(parts[2].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
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