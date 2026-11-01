package com.dwinovo.numen.rdd.policy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 166 §77：卡死提醒必须报出「判据是什么、现在有几个」。
 *
 * <p>这几条同时防止两种坏掉的方式：把话说成空话（等于没修），
 * 或者把 group 条件当成具体物品报一个假的持有量。
 */
class StallHintTest {

    @Test
    void namesTheKeyAndTheHeldCount() {
        String s = StallHint.forCondition(
                Map.of("type", "inventory", "asset_key", "minecraft:white_bed", "minimum", 1), 0);
        assertTrue(s.contains("minecraft:white_bed"), s);
        assertTrue(s.contains("你现在有 0 个"), s);
        assertTrue(s.contains("至少 1"), s);
    }

    @Test
    void withoutMinimumStillSaysSomethingUsable() {
        String s = StallHint.forCondition(Map.of("asset_key", "minecraft:torch"), 3);
        assertTrue(s.contains("你现在有 3 个"), s);
        assertTrue(s.contains("至少 1"), "没有 minimum 时也要给出一个可读的下限: " + s);
    }

    @Test
    void groupConditionDoesNotFakeAnItemCount() {
        String s = StallHint.forCondition(Map.of("group", "food"), 0);
        assertTrue(s.contains("food"), s);
        assertTrue(s.contains("一组"), "组条件不该被报成某个具体物品的持有量: " + s);
    }

    @Test
    void noConditionNoNoise() {
        assertEquals("", StallHint.forCondition(null, 0));
        assertEquals("", StallHint.forCondition(Map.of(), 5));
        assertEquals("", StallHint.forCondition(Map.of("type", "position"), 5), "位置类条件不该拼缺口句");
    }
}
