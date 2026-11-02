package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.core.DangerSpots;
import com.dwinovo.numen.rdd.core.RddDeathLedger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 历史危险点进上下文（2026-11-01，167 §9.7）。
 *
 * <p>与 {@link RddDangerFacts} 的分工：那个说「你**现在**正在被打」，这个说「你**以前**在这里死过不止一次」。
 *
 * <p>为什么不能只靠 repeat_death_nearby：它的窗口是 180 秒，而实机里同一坐标的两次摔死相隔 93 分钟，
 * 按设计根本不会触发，于是「这地方摔死过你」这条事实没人记得。
 * 这里从死亡台账做长期聚类（>=2 次、8 格内），只陈述事实，不替模型决定去不去。
 */
final class RddDeathSpotFacts {

    private RddDeathSpotFacts() {}

    static String blockFor(UUID companionId) {
        if (companionId == null) return "";
        try {
            List<DangerSpots.DeathAt> deaths = new ArrayList<>();
            for (RddDeathLedger.Death d : RddDeathLedger.all(companionId)) {
                int[] xyz = parse(d.deathAt());
                if (xyz == null) continue;
                deaths.add(new DangerSpots.DeathAt(d.seq(), xyz[0], xyz[1], xyz[2], d.gameTime()));
            }
            if (deaths.isEmpty()) return "";
            var spots = DangerSpots.find(deaths, DangerSpots.DEFAULT_RADIUS, 2);
            if (spots.isEmpty()) return "";
            return "<death_spots>\n" + DangerSpots.describe(spots)
                    + "\n（这些地点你死过不止一次：接近前先想清楚怎么回来）\n</death_spots>";
        } catch (RuntimeException ex) {
            return "";
        }
    }

    /**
     * 2026-11-02：把最近死亡写成规划侧的 knownFailures（纯函数、可单测）。
     *
     * <p>为什么需要：`RddDecomposer` 的 knownFailures 原先只在 attempt≥1 时塞
     * 「上一次子步骤不可执行」，死亡原因从不进入 ⇒ 规划侧的 experience 检索召不回
     * 死亡教训（只能靠上下文里的 <death_spots> 被动看到）。这里把「最近死亡原因+坐标」
     * 和「同点复死」变成检索依据，最多 {@link #MAX_FAILURES} 条，避免刷爆 prompt。
     */
    static List<String> knownFailures(List<RddDeathLedger.Death> deaths) {
        if (deaths == null || deaths.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        int from = Math.max(0, deaths.size() - 3);
        for (int i = deaths.size() - 1; i >= from && out.size() < MAX_FAILURES; i--) {
            RddDeathLedger.Death d = deaths.get(i);
            String cause = d.cause() == null || d.cause().isBlank() ? "unknown" : d.cause();
            String at = d.deathAt() == null || d.deathAt().isBlank() ? "?" : d.deathAt();
            out.add("在此前死亡：" + cause + " @ " + at
                    + (d.lostEntries() > 0 ? "（丢 " + d.lostEntries() + " 类）" : ""));
        }
        try {
            List<DangerSpots.DeathAt> spots = new ArrayList<>();
            for (RddDeathLedger.Death d : deaths) {
                int[] xyz = parse(d.deathAt());
                if (xyz != null) spots.add(new DangerSpots.DeathAt(d.seq(), xyz[0], xyz[1], xyz[2], d.gameTime()));
            }
            for (var spot : DangerSpots.find(spots, DangerSpots.DEFAULT_RADIUS, 2)) {
                if (out.size() >= MAX_FAILURES) break;
                out.add("同一地点已死 " + spot.deaths() + " 次：" + spot.x() + ", " + spot.y() + ", " + spot.z());
            }
        } catch (RuntimeException ignored) {
            // 聚类失败不影响前几条；事实块失败不许拖垮规划
        }
        return List.copyOf(out);
    }

    /** 从台账取（宿主侧便捷入口；读不到返回空）。 */
    static List<String> knownFailuresFor(UUID companionId) {
        if (companionId == null) return List.of();
        try {
            return knownFailures(RddDeathLedger.all(companionId));
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    static final int MAX_FAILURES = 4;

    /** deathAt 是 "x, y, z" 短文本；解析不了返回 null（不猜）。 */
    private static int[] parse(String deathAt) {
        if (deathAt == null) return null;
        String[] parts = deathAt.split("[,\\s]+");
        if (parts.length != 3) return null;
        try {
            return new int[]{Integer.parseInt(parts[0].trim()),
                    Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim())};
        } catch (NumberFormatException notCoordinates) {
            return null;
        }
    }
}