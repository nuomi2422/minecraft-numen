package com.dwinovo.numen.rdd.policy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 资产键构造：药水效果 → 预算键（166 B3 的永久拦停就是这条规则缺失导致的）。 */
class AssetKeysTest {

    @Test
    void 抗火药映射到预算键() {
        assertEquals("minecraft:fire_resistance_potion", AssetKeys.potionKey("fire_resistance"));
    }

    @Test
    void 带命名空间也认() {
        assertEquals("minecraft:fire_resistance_potion", AssetKeys.potionKey("minecraft:fire_resistance"));
        assertEquals("minecraft:healing_potion", AssetKeys.potionKey("Minecraft:Healing"));
    }

    @Test
    void 普通水药水也能映射_说明映射是通例而非验收特例() {
        // 166 B3 的对照：如果只有抗火被特判，那这条会失败。
        // 普通药水（water）能映射，说明映射是通例而不是为验收开的特例。
        assertEquals("minecraft:water_potion", AssetKeys.potionKey("water"));
        assertEquals("minecraft:healing_potion", AssetKeys.potionKey("healing"));
    }

    @Test
    void 空效果不生成假键() {
        assertEquals("", AssetKeys.potionKey(null));
        assertEquals("", AssetKeys.potionKey(""));
        assertEquals("", AssetKeys.potionKey("   "));
        assertEquals("", AssetKeys.potionKey("minecraft:"), "只有命名空间时不许产出 minecraft:_potion");
    }

    @Test
    void 生成的键必须能对上预算表里的形状() {
        // 这条把两侧钉在一起：ResourceBudget 要求什么形状，这里就必须产出什么形状。
        String key = AssetKeys.potionKey("fire_resistance");
        assertTrue(key.endsWith("_potion"), "预算表里药水类键都以 _potion 结尾");
        assertTrue(key.startsWith("minecraft:"), "预算表用带命名空间的 id");
    }
}
