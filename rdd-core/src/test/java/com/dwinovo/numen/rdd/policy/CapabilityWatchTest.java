package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「刚拿到能改变计划的东西」（{@link CapabilityWatch}）：2026-10-28 主人第六点后半 + 提前收口的触发层。
 *
 * <p>它只回答"要不要把规划者叫醒、叫醒时摆什么事实"，<b>不回答"该不该收口"</b>。
 * 所以断言集中在"叫得准"与"叫得少"：从无到有、装备或已声明语义、IRRELEVANT 沉默、有界。
 */
class CapabilityWatchTest {

    private static Map<String, Integer> counts(String... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(kv[i], Integer.parseInt(kv[i + 1]));
        }
        return m;
    }

    @Test
    void equipmentAppearingFromZeroIsReported() {
        List<CapabilityWatch.Upgrade> ups = CapabilityWatch.newlyObtained(
                Map.of(), counts("minecraft:diamond_pickaxe", "1"), Map.of());
        assertEquals(1, ups.size());
        assertEquals("minecraft:diamond_pickaxe", ups.get(0).assetKey());
        assertEquals(AssetSemantics.Nature.EQUIPMENT, ups.get(0).nature());
        assertTrue(ups.get(0).render().contains("装备"), "没声明语义时也要说清它是装备：" + ups.get(0).render());
    }

    @Test
    void plainMaterialWithoutSemanticsStaysSilent() {
        assertTrue(CapabilityWatch.newlyObtained(
                Map.of(), counts("minecraft:dirt", "5"), Map.of()).isEmpty(),
                "普通材料从无到有不值得打断（否则她会被自己的背包叫醒）");
    }

    @Test
    void declaredSemanticsAreCarriedIncludingCapabilityAndSubstitutes() {
        Map<String, AssetSemantics> sem = Map.of("minecraft:diamond_pickaxe", new AssetSemantics(
                "minecraft:diamond_pickaxe", AssetSemantics.Importance.IMPORTANT,
                AssetSemantics.Nature.EQUIPMENT, "拿到钻石镐", "能挖黑曜石",
                "declared", List.of("minecraft:iron_pickaxe")));

        List<CapabilityWatch.Upgrade> ups = CapabilityWatch.newlyObtained(
                Map.of(), counts("minecraft:diamond_pickaxe", "1"), sem);

        assertEquals(1, ups.size());
        assertEquals("能挖黑曜石", ups.get(0).capability());
        assertTrue(ups.get(0).render().contains("可替代：minecraft:iron_pickaxe"),
                "替代条件必须原样摆出来 —— 决定权在规划者，不在代码：" + ups.get(0).render());
    }

    @Test
    void irrelevantSemanticsAreSilent() {
        Map<String, AssetSemantics> sem = Map.of("minecraft:diamond_pickaxe", new AssetSemantics(
                "minecraft:diamond_pickaxe", AssetSemantics.Importance.IRRELEVANT,
                AssetSemantics.Nature.EQUIPMENT, "", "", "UNKNOWN", List.of()));
        assertTrue(CapabilityWatch.newlyObtained(Map.of(), counts("minecraft:diamond_pickaxe", "1"), sem).isEmpty(),
                "已声明「与当前目标无关」的东西不许再叫醒");
    }

    @Test
    void quantityIncreaseIsNotANewCapability() {
        assertTrue(CapabilityWatch.newlyObtained(
                counts("minecraft:iron_ingot", "1"), counts("minecraft:iron_ingot", "5"), Map.of()).isEmpty(),
                "1 → 5 只是量变，不是新能力；否则每挖一块矿都叫一次");
    }

    @Test
    void isBoundedSoTheContextNeverExplodes() {
        Map<String, Integer> now = new LinkedHashMap<>();
        for (int i = 0; i < 10; i++) {
            now.put("minecraft:tool_" + i + "_pickaxe", 1);
        }
        List<CapabilityWatch.Upgrade> ups = CapabilityWatch.newlyObtained(Map.of(), now, Map.of());
        assertEquals(CapabilityWatch.MAX_UPGRADES, ups.size());
    }

    @Test
    void importantKeysExposeOnlyWhatIsDeclaredImportant() {
        Map<String, AssetSemantics> sem = Map.of(
                "minecraft:diamond_pickaxe", AssetSemantics.declared("minecraft:diamond_pickaxe",
                        AssetSemantics.Importance.IMPORTANT, AssetSemantics.Nature.EQUIPMENT, "g"),
                "minecraft:dirt", AssetSemantics.declared("minecraft:dirt",
                        AssetSemantics.Importance.SECONDARY, AssetSemantics.Nature.MATERIAL, "g"));
        assertEquals(Set.of("minecraft:diamond_pickaxe"), CapabilityWatch.importantKeys(sem));
    }
}
