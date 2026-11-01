package com.dwinovo.numen.rdd.policy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 166 §73（主人口径）：下界门槛 = 一套钻石甲 + 两套铁甲 + 火把/食物，抗火药可选。
 *
 * <p>为什么不是两套钻石：它还没有「基地=仓库」的意识，装备全在身上，
 * 抬钻石数量只会把前置拖成终局；铁甲便宜、可成建制补充，用来保证「死一次还能再开」。
 *
 * <p>这组用例钉住：铁甲是真要求（缺了要拦）、钻石甲仍是真要求（缺了要拦）、
 * 食物/火把底线没被顺手削掉、末地不受影响。
 */
class NetherArmorSingleSetTest {

    private static Map<String, Integer> kit() {
        Map<String, Integer> m = new HashMap<>();
        m.put("minecraft:diamond_helmet", 1);
        m.put("minecraft:diamond_chestplate", 1);
        m.put("minecraft:diamond_leggings", 1);
        m.put("minecraft:diamond_boots", 1);
        m.put("minecraft:iron_helmet", 2);
        m.put("minecraft:iron_chestplate", 2);
        m.put("minecraft:iron_leggings", 2);
        m.put("minecraft:iron_boots", 2);
        m.put("minecraft:torch", 16);
        m.put("minecraft:cooked_beef", 16);
        return m;
    }

    @Test
    void diamondSetPlusTwoIronSetsIsEnough() {
        RiskGate.Verdict v = RiskGate.check(RiskLevel.NETHER, kit());
        assertTrue(v.allowed(), "一套钻石甲 + 两套铁甲 + 火把 + 食物应当过门（抗火药按 §69 可选）");
        assertTrue(v.missing().isEmpty() || v.missing().stream()
                        .allMatch(s -> s.startsWith("minecraft:fire_resistance_potion")),
                "除抗火药外不应再有缺口");
    }

    @Test
    void ironBackupIsARealRequirement() {
        Map<String, Integer> oneIronSet = kit();
        oneIronSet.put("minecraft:iron_helmet", 1);
        oneIronSet.put("minecraft:iron_boots", 1);
        assertFalse(RiskGate.check(RiskLevel.NETHER, oneIronSet).allowed(),
                "只有一套铁甲时应当拦：备用是这条门槛存在的理由");
    }

    @Test
    void diamondSetIsStillRequired() {
        Map<String, Integer> noDiamond = kit();
        noDiamond.remove("minecraft:diamond_chestplate");
        assertFalse(RiskGate.check(RiskLevel.NETHER, noDiamond).allowed(), "缺钻石甲仍要拦");
    }

    @Test
    void foodTorchFloorsAndEndUnaffected() {
        Map<String, Integer> noFood = kit();
        noFood.remove("minecraft:cooked_beef");
        assertFalse(RiskGate.check(RiskLevel.NETHER, noFood).allowed(), "食物底线不能被这次改动带松");
        assertFalse(RiskGate.check(RiskLevel.END, Map.of()).allowed(), "末地不受影响");
    }
}
