package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 166 §72：下界护甲门槛从「两套」降到「一套」。
 *
 * <p>钉住两件事：一是**降的是数量、不是要求**（护甲仍然必须齐四件）；
 * 二是别顺手把食物/火把的底线也削了。
 */
class NetherArmorSingleSetTest {

    private static Map<String, Integer> oneSetPlusRest() {
        return Map.of(
                "minecraft:diamond_helmet", 1,
                "minecraft:diamond_chestplate", 1,
                "minecraft:diamond_leggings", 1,
                "minecraft:diamond_boots", 1,
                "minecraft:torch", 16,
                "minecraft:cooked_beef", 16);
    }

    @Test
    void oneFullSetIsEnoughForNether() {
        RiskGate.Verdict v = RiskGate.check(RiskLevel.NETHER, oneSetPlusRest());
        assertTrue(v.allowed(), "一套钻石甲 + 火把 + 食物就应当能过（抗火药按 §69 可选）");
    }

    @Test
    void stillRequiresAllFourPieces() {
        Map<String, Integer> noBoots = Map.of(
                "minecraft:diamond_helmet", 1,
                "minecraft:diamond_chestplate", 1,
                "minecraft:diamond_leggings", 1,
                "minecraft:torch", 16,
                "minecraft:cooked_beef", 16);
        assertFalse(RiskGate.check(RiskLevel.NETHER, noBoots).allowed(), "缺一件护甲仍要拦");
    }

    @Test
    void foodAndTorchFloorsUnchanged() {
        Map<String, Integer> noFood = Map.of(
                "minecraft:diamond_helmet", 1,
                "minecraft:diamond_chestplate", 1,
                "minecraft:diamond_leggings", 1,
                "minecraft:diamond_boots", 1,
                "minecraft:torch", 16);
        assertFalse(RiskGate.check(RiskLevel.NETHER, noFood).allowed(), "食物底线不能被这次改动带松");
        assertFalse(RiskGate.check(RiskLevel.END, Map.of()).allowed(), "末地不受影响");
    }
}
