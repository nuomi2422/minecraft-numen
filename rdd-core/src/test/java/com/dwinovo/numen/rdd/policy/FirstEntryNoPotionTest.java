package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A/166 §69：首次下界允许缺抗火药，但**只放过这一项**。
 *
 * <p>这条规则的风险在于"顺手把别的也放过"，所以三个用例把边界钉死：
 * 只缺抗火药 → 放行；缺抗火药 + 任何别的 → 不放行；非下界 → 不受影响。
 */
class FirstEntryNoPotionTest {

    /** 下界要求的其余六项齐全，唯独没有抗火药。 */
    private static Map<String, Integer> everythingButPotion() {
        return Map.of(
                "minecraft:diamond_helmet", 2,
                "minecraft:diamond_chestplate", 2,
                "minecraft:diamond_leggings", 2,
                "minecraft:diamond_boots", 2,
                "minecraft:torch", 16,
                "minecraft:cooked_beef", 16);
    }

    @Test
    void missingOnlyPotionIsAllowedForFirstEntry() {
        RiskGate.Verdict v = RiskGate.check(RiskLevel.NETHER, everythingButPotion());
        assertTrue(v.allowed(), "只缺抗火药时应当放行（否则抗火药=下界材料，永远进不去）");
        // 但缺口仍要如实报出来：证据里必须看得到"这次是没带药进去的"。
        assertFalse(v.missing().isEmpty(), "放行不等于装作没有缺口");
        assertTrue(v.missing().stream().allMatch(m -> m.startsWith("minecraft:fire_resistance_potion")));
        assertFalse(v.prepTasks().isEmpty(), "放行时必须带上「进后立刻返回」这句话");
    }

    @Test
    void missingPotionPlusAnythingElseStaysBlocked() {
        Map<String, Integer> weak = Map.of(
                "minecraft:torch", 16,
                "minecraft:cooked_beef", 16);   // 护甲全无
        RiskGate.Verdict v = RiskGate.check(RiskLevel.NETHER, weak);
        assertFalse(v.allowed(), "缺护甲/食物时绝不能因为这条规则放行");
    }

    @Test
    void normalAndEndUnaffected() {
        assertTrue(RiskGate.check(RiskLevel.NORMAL, Map.of()).allowed());
        // 末地没有这条豁免：缺什么就拦什么。
        assertFalse(RiskGate.check(RiskLevel.END, Map.of()).allowed());
    }
}
