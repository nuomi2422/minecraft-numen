package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** P2.2 风险门：硬判定 + 缺口转准备任务 + 风险级别识别。 */
class RiskGateTest {

    @Test void blocksNetherWhenUnderPrepared() {
        RiskGate.Verdict v = RiskGate.check(RiskLevel.NETHER,
                Map.of("minecraft:diamond_chestplate", 1, "minecraft:torch", 16));
        assertFalse(v.allowed());
        assertTrue(v.missing().stream().anyMatch(s -> s.contains("fire_resistance_potion")));
        assertTrue(v.prepTasks().stream().anyMatch(s -> s.contains("酿造") || s.contains("potion")));
        assertTrue(v.prepTasks().stream().anyMatch(s -> s.contains("临时据点") || s.contains("回退")));
    }

    @Test void allowsNetherWhenPrepared() {
        // 2026-10-31（166 §73）：门槛改为「1 套钻石 + 2 套铁 + 火把/食物，抗火药可选」，
        // fixture 必须跟着契约走，否则这几条用例测的还是旧契约。
        var ok = Map.ofEntries(
                Map.entry("minecraft:diamond_helmet", 1), Map.entry("minecraft:diamond_chestplate", 1),
                Map.entry("minecraft:diamond_leggings", 1), Map.entry("minecraft:diamond_boots", 1),
                Map.entry("minecraft:iron_helmet", 2), Map.entry("minecraft:iron_chestplate", 2),
                Map.entry("minecraft:iron_leggings", 2), Map.entry("minecraft:iron_boots", 2),
                Map.entry("minecraft:fire_resistance_potion", 3), Map.entry("minecraft:torch", 16),
                Map.entry("minecraft:cooked_beef", 16));
        RiskGate.Verdict v = RiskGate.check(RiskLevel.NETHER, ok);
        assertTrue(v.allowed());
        assertTrue(v.missing().isEmpty());
    }

    @Test void normalAlwaysAllows() {
        assertTrue(RiskGate.allows(RiskLevel.NORMAL, Map.of()));
    }

    @Test void levelDetection() {
        assertEquals(RiskLevel.NETHER, RiskGate.levelForDimension("minecraft:the_nether"));
        assertEquals(RiskLevel.END, RiskGate.levelForDimension("minecraft:the_end"));
        assertEquals(RiskLevel.NORMAL, RiskGate.levelForDimension("minecraft:overworld"));
        assertEquals(RiskLevel.NETHER, RiskGate.levelForText("进入下界准备"));
        assertEquals(RiskLevel.END, RiskGate.levelForText("击杀末影龙"));
        assertEquals(RiskLevel.MINING, RiskGate.levelForText("下矿采集钻石"));
        assertEquals(RiskLevel.NORMAL, RiskGate.levelForText("做石镐"));
    }

    /** §6③：高风险活动需恢复点（已绑床）；没恢复点即使物资够也阻止。 */
    @Test void highRiskRequiresRecoveryPoint() {
        // 2026-10-31（166 §73）：门槛改为「1 套钻石 + 2 套铁 + 火把/食物，抗火药可选」，
        // fixture 必须跟着契约走，否则这几条用例测的还是旧契约。
        var ok = Map.ofEntries(
                Map.entry("minecraft:diamond_helmet", 1), Map.entry("minecraft:diamond_chestplate", 1),
                Map.entry("minecraft:diamond_leggings", 1), Map.entry("minecraft:diamond_boots", 1),
                Map.entry("minecraft:iron_helmet", 2), Map.entry("minecraft:iron_chestplate", 2),
                Map.entry("minecraft:iron_leggings", 2), Map.entry("minecraft:iron_boots", 2),
                Map.entry("minecraft:fire_resistance_potion", 3), Map.entry("minecraft:torch", 16),
                Map.entry("minecraft:cooked_beef", 16));
        var noRecovery = RiskGate.checkWithRecovery(RiskLevel.NETHER, ok, false);
        assertFalse(noRecovery.allowed(), "物资够但没恢复点 → 阻止");
        assertTrue(noRecovery.missing().stream().anyMatch(s -> s.contains("recovery_point")));
        assertTrue(noRecovery.prepTasks().stream().anyMatch(s -> s.contains("绑定重生点") || s.contains("床")));

        var withRecovery = RiskGate.checkWithRecovery(RiskLevel.NETHER, ok, true);
        assertTrue(withRecovery.allowed(), "物资够 + 有恢复点 → 允许");
    }

    @Test void recoveryPointNotRequiredForNormalOrMining() {
        assertTrue(RiskGate.checkWithRecovery(RiskLevel.NORMAL, Map.of(), false).allowed());
        assertTrue(RiskGate.checkWithRecovery(RiskLevel.MINING,
                Map.of("minecraft:torch", 16, "minecraft:bread", 4), false).allowed(),
                "下矿不强制恢复点");
    }

    /**
     * B3/166：禁止项不能被判成「要去」。
     *
     * <p>2026-09-28 风险门被整个关掉的根因就在这里：「也绝不进入下界」含「进入」+「下界」，
     * 被判成 NETHER，于是给"原地待命"挂上下界硬门，任务永远派不下去。
     * 修法是只跳过被否定的那一处命中，不放弃整段文本的行动判定。
     */
    @Test void negatedTargetIsNotTreatedAsAction() {
        assertEquals(RiskLevel.NORMAL, RiskGate.levelForText("也绝不进入下界"),
                "★ 禁止项不能判成 NETHER —— 这是风险门被迫关闭的历史根因（B3 回归钉）");
        assertEquals(RiskLevel.NORMAL, RiskGate.levelForText("不要前往下界"),
                "「不要前往下界」是否定，不是行动");
        assertEquals(RiskLevel.NORMAL, RiskGate.levelForText("禁止进入末地"),
                "「禁止进入末地」是否定，不是行动");
        assertEquals(RiskLevel.NORMAL, RiskGate.levelForText("暂不下矿"),
                "「暂不下矿」是否定，不是行动");
    }

    @Test void negationGuardDoesNotSwallowRealActions() {
        assertEquals(RiskLevel.NETHER, RiskGate.levelForText("进入下界采集烈焰棒"),
                "正常行动仍必须判 NETHER");
        assertEquals(RiskLevel.NETHER, RiskGate.levelForText("先补装备，然后进入下界"),
                "同一句里有前缀也不是否定，不能被误吞");
        // 同一段文本里，被否定的一处跳过，另一处真正的行动仍然生效
        assertEquals(RiskLevel.NETHER, RiskGate.levelForText("不要停留，直接进入下界"),
                "★ 只跳过被否定目标词，不能因为全句含否定就把真行动也一起放过");
    }
}
