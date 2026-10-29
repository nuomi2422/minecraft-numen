package com.dwinovo.numen.rdd.policy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 资源储备预算（P2.1）：按 {@link RiskLevel} 给出“进入该风险活动前的最低储备”。
 *
 * <p>解决“规划 AI 没有长期资源观”：不是拿够当前任务，而是按未来风险预留
 * （探索/战斗/死亡恢复都会消耗）。RiskGate（P2.2）据此判“允许/阻止/缺什么”，
 * 与 {@link AssetPurposeStore}（用途/备用）共用。
 *
 * <p>数值是保守基线数据，可调整；键用真实 minecraft 命名空间 ID（mod 兼容由 unknown 键容忍）。
 */
public final class ResourceBudget {

    private static final Map<RiskLevel, Map<String, Integer>> REQUIRED = Map.of(
            RiskLevel.NORMAL, Map.of(),
            RiskLevel.MINING, Map.of(
                    "minecraft:torch", 16,
                    "minecraft:bread", 4),
            RiskLevel.NETHER, Map.of(
                    "minecraft:diamond_helmet", 2,
                    "minecraft:diamond_chestplate", 2,
                    "minecraft:diamond_leggings", 2,
                    "minecraft:diamond_boots", 2,
                    "minecraft:fire_resistance_potion", 3,
                    "minecraft:torch", 16,
                    "minecraft:cooked_beef", 16),
            RiskLevel.END, Map.of(
                    "minecraft:diamond_chestplate", 1,
                    "minecraft:bow", 1,
                    "minecraft:arrow", 32,
                    "minecraft:ender_pearl", 12,
                    "minecraft:water_bucket", 1,
                    "minecraft:cooked_beef", 16,
                    "minecraft:golden_apple", 2)
    );

    private ResourceBudget() {}

    /**
     * A2（94 号）：高风险活动的「备用套」——不是多带一份同样的，而是**丢掉一套还能再开**。
     *
     * <p>晚近实据（88 §7.2）：末地链连续死亡 8 次，没有仓库和备用装备，死一次就裸奔，
     * 只能靠人工 revive/give 再开。这里给下界/末地加最低备用：一套工具、一份食物、
     * 一个水桶/床。与基线 {@link #requiredFor} 用 max 合并（同 key 不重复计）。
     *
     * <p>只给 NETHER/END：普通采集/下矿加备用会逼她提前重复采集，反而拖慢第一套。
     */
    private static final Map<RiskLevel, Map<String, Integer>> BACKUP = Map.of(
            RiskLevel.NORMAL, Map.of(),
            RiskLevel.MINING, Map.of(),
            RiskLevel.NETHER, Map.of(
                    "minecraft:cooked_beef", 32,
                    "minecraft:white_bed", 1,
                    "minecraft:iron_pickaxe", 2,
                    "minecraft:iron_sword", 2),
            RiskLevel.END, Map.of(
                    "minecraft:cooked_beef", 32,
                    "minecraft:golden_apple", 4,
                    "minecraft:arrow", 64,
                    "minecraft:water_bucket", 2,
                    "minecraft:white_bed", 1)
    );

    /** 该风险级别要求的「备用套」（空 = 该级别不要求备用）。 */
    public static Map<String, Integer> backupFor(RiskLevel level) {
        return BACKUP.getOrDefault(level, Map.of());
    }

    /**
     * 基线 + 备用，按 key 取较大 minimum。NORMAL/MINING 与 {@link #requiredFor} 完全一致
     * （不改变低风险行为的既有判据）。
     */
    public static Map<String, Integer> requiredWithBackup(RiskLevel level) {
        Map<String, Integer> base = requiredFor(level);
        Map<String, Integer> backup = backupFor(level);
        if (backup.isEmpty()) {
            return base;
        }
        Map<String, Integer> out = new LinkedHashMap<>(base);
        for (Map.Entry<String, Integer> e : backup.entrySet()) {
            out.merge(e.getKey(), e.getValue(), Math::max);
        }
        return Map.copyOf(out);
    }

    /** 该风险级别要求的最低储备（不可变）。 */
    public static Map<String, Integer> requiredFor(RiskLevel level) {
        return REQUIRED.getOrDefault(level, Map.of());
    }

    /**
     * 相对该风险级别还缺什么：返回 "key need X have Y" 列表（空 = 达标或该级别无要求）。
     * 数值来自真实可用计数（通常 {@code PlanningAssetSnapshot.availableCounts()}）。
     */
    public static List<String> missingFor(RiskLevel level, Map<String, Integer> available) {
        return missingFrom(requiredFor(level), available);
    }

    /** A2：含备用套的缺口（prepHint 用；与 {@link #requiredWithBackup} 同口径）。 */
    public static List<String> missingWithBackup(RiskLevel level, Map<String, Integer> available) {
        return missingFrom(requiredWithBackup(level), available);
    }

    private static List<String> missingFrom(Map<String, Integer> need, Map<String, Integer> available) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : need.entrySet()) {
            int have = available == null ? 0 : available.getOrDefault(e.getKey(), 0);
            if (have < e.getValue()) {
                out.add(e.getKey() + " need " + e.getValue() + " have " + have);
            }
        }
        return out;
    }

    /** 是否达标（无缺口）。 */
    public static boolean satisfied(RiskLevel level, Map<String, Integer> available) {
        return missingFor(level, available).isEmpty();
    }
}
