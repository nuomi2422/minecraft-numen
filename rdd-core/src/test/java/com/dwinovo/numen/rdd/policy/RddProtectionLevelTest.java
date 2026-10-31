package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B6 / S6 的两条硬约束（166 §48.5 判据 1、2）。
 *
 * <p>判据 1：没有连续死亡时，加码接口必须与旧接口**逐项相等** ——
 * 否则 B3/B5 已经验证过的门行为会被悄悄改掉。
 *
 * <p>判据 2：HIGH 时备用套取「升一档」（NETHER -> END）。
 */
class RddProtectionLevelTest {

    @Test
    void noRecentDeathsMeansLowAndOldBehaviourIsUnchanged() {
        assertEquals(RddProtectionLevel.LOW, RddProtectionLevel.of(0));
        assertEquals(RddProtectionLevel.NORMAL, RddProtectionLevel.of(1));
        assertEquals(RddProtectionLevel.HIGH, RddProtectionLevel.of(2));
        assertEquals(RddProtectionLevel.HIGH, RddProtectionLevel.of(7));

        for (RiskLevel level : RiskLevel.values()) {
            for (RddProtectionLevel p : new RddProtectionLevel[]{null, RddProtectionLevel.LOW, RddProtectionLevel.NORMAL}) {
                assertEquals(ResourceBudget.requiredWithBackup(level),
                        ResourceBudget.requiredWithBackup(level, p),
                        "非 HIGH 时不得改变任何级别的备用要求: " + level + " / " + p);
            }
        }
    }

    @Test
    void highEscalatesNetherBackupToEnd() {
        Map<String, Integer> escalated = ResourceBudget.requiredWithBackup(RiskLevel.NETHER, RddProtectionLevel.HIGH);
        Map<String, Integer> endBackup = ResourceBudget.backupFor(RiskLevel.END);
        Map<String, Integer> netherBase = ResourceBudget.requiredFor(RiskLevel.NETHER);

        for (Map.Entry<String, Integer> e : endBackup.entrySet()) {
            int need = Math.max(netherBase.getOrDefault(e.getKey(), 0), e.getValue());
            assertEquals(need, escalated.get(e.getKey()),
                    "连死两次后，" + e.getKey() + " 应取末地备用标准");
        }
        assertTrue(escalated.size() >= netherBase.size());
    }

    @Test
    void escalationIsOnlyWhenLevelActuallyRises() {
        // 首次（基线未建立）不发：否则每一次"第一条死亡"都会误报升级。
        assertFalse(RddProtectionLevel.isEscalationFrom(null, RddProtectionLevel.NORMAL));
        assertFalse(RddProtectionLevel.isEscalationFrom(null, RddProtectionLevel.HIGH));
        // 持平不发。
        assertFalse(RddProtectionLevel.isEscalationFrom(RddProtectionLevel.NORMAL, RddProtectionLevel.NORMAL));
        // 下降不发（下面那句警告是"保护被撤销"的错觉来源）。
        assertFalse(RddProtectionLevel.isEscalationFrom(RddProtectionLevel.HIGH, RddProtectionLevel.NORMAL));
        // 上升才发，且允许连跳两级。
        assertTrue(RddProtectionLevel.isEscalationFrom(RddProtectionLevel.LOW, RddProtectionLevel.NORMAL));
        assertTrue(RddProtectionLevel.isEscalationFrom(RddProtectionLevel.NORMAL, RddProtectionLevel.HIGH));
        assertTrue(RddProtectionLevel.isEscalationFrom(RddProtectionLevel.LOW, RddProtectionLevel.HIGH));
    }

    @Test
    void highOnEndStaysEnd() {
        assertEquals(RiskLevel.END, ResourceBudget.escalatedLevel(RiskLevel.END));
        assertEquals(ResourceBudget.requiredWithBackup(RiskLevel.END),
                ResourceBudget.requiredWithBackup(RiskLevel.END, RddProtectionLevel.HIGH));
    }
}
