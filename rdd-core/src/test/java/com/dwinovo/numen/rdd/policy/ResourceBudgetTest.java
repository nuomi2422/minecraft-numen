package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** P2.1 资源储备预算：按风险级别算缺口。 */
class ResourceBudgetTest {

    @Test void normalHasNoRequirement() {
        assertTrue(ResourceBudget.satisfied(RiskLevel.NORMAL, Map.of()));
        assertTrue(ResourceBudget.requiredFor(RiskLevel.NORMAL).isEmpty());
    }

    @Test void netherRequiresTwoSetsDiamondArmorAndPotions() {
        var need = ResourceBudget.requiredFor(RiskLevel.NETHER);
        assertEquals(2, need.get("minecraft:diamond_chestplate"));
        assertEquals(3, need.get("minecraft:fire_resistance_potion"));
        // 只有一套甲 + 没药水 → 报缺口
        var missing = ResourceBudget.missingFor(RiskLevel.NETHER,
                Map.of("minecraft:diamond_chestplate", 1, "minecraft:torch", 16));
        assertTrue(missing.stream().anyMatch(s -> s.contains("diamond_chestplate") && s.contains("need 2")));
        assertTrue(missing.stream().anyMatch(s -> s.contains("fire_resistance_potion")));
        assertFalse(ResourceBudget.satisfied(RiskLevel.NETHER, Map.of("minecraft:diamond_chestplate", 1)));
    }

    @Test void satisfiedWhenAllPresent() {
        var available = Map.of(
                "minecraft:diamond_helmet", 2, "minecraft:diamond_chestplate", 2,
                "minecraft:diamond_leggings", 2, "minecraft:diamond_boots", 2,
                "minecraft:fire_resistance_potion", 3, "minecraft:torch", 16,
                "minecraft:cooked_beef", 16);
        assertTrue(ResourceBudget.satisfied(RiskLevel.NETHER, available));
    }

    // ---- A2（94 号）：备用套 ----

    @Test void backupOnlyForHighRisk() {
        assertTrue(ResourceBudget.backupFor(RiskLevel.NORMAL).isEmpty(), "普通不要求备用");
        assertTrue(ResourceBudget.backupFor(RiskLevel.MINING).isEmpty(), "下矿不要求备用（避免拖慢第一套）");
        assertFalse(ResourceBudget.backupFor(RiskLevel.NETHER).isEmpty());
        assertFalse(ResourceBudget.backupFor(RiskLevel.END).isEmpty());
    }

    @Test void requiredWithBackupMergesByMaxAndKeepsBaseKeys() {
        var base = ResourceBudget.requiredFor(RiskLevel.END);
        var withBackup = ResourceBudget.requiredWithBackup(RiskLevel.END);
        for (var k : base.keySet()) {
            assertTrue(withBackup.containsKey(k), "基线键不能丢: " + k);
            assertTrue(withBackup.get(k) >= base.get(k), "备用只能抬高不能降低: " + k);
        }
        assertEquals(64, withBackup.get("minecraft:arrow"), "末地箭备用 64（基线 32 取大）");
        assertEquals(32, withBackup.get("minecraft:cooked_beef"), "末地食物备用 32");
    }

    @Test void backupDoesNotChangeNormalOrMiningRequirements() {
        assertEquals(ResourceBudget.requiredFor(RiskLevel.NORMAL), ResourceBudget.requiredWithBackup(RiskLevel.NORMAL));
        assertEquals(ResourceBudget.requiredFor(RiskLevel.MINING), ResourceBudget.requiredWithBackup(RiskLevel.MINING));
    }

    @Test void missingWithBackupReportsBackupGap() {
        // 只有基线数量、没有备用 → 仍报缺口（这正是「死一次就裸奔」的账）
        var available = Map.of("minecraft:cooked_beef", 16, "minecraft:arrow", 32);
        var missing = ResourceBudget.missingWithBackup(RiskLevel.END, available);
        assertTrue(missing.stream().anyMatch(s -> s.contains("cooked_beef") && s.contains("need 32")));
        assertTrue(missing.stream().anyMatch(s -> s.contains("arrow") && s.contains("need 64")));
    }
}
