package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 两条支线提醒的护栏（纯文本）。
 *
 * <p>纪律：**只在确有可行动作时说话**——
 * 空槽+背包无对应护甲不说话；饱食度正常不说话。否则变成噪音，比不提示更糟。
 */
class RddNeedsTextTest {

    @Test
    void 空槽有更好护甲时提示换() {
        String s = RddNeedsText.gear("胸甲", "", "钻石胸甲", 2);
        assertTrue(s.contains("<gear_hint>") && s.contains("</gear_hint>"));
        assertTrue(s.contains("空的"), "空槽要说清楚是空的");
        assertTrue(s.contains("钻石胸甲"));
        assertTrue(s.contains("×2"), "多件要报数量");
        assertTrue(s.contains("equip_item"), "必须给出可执行的动作");
    }

    @Test
    void 身上更差时提示升级() {
        String s = RddNeedsText.gear("头盔", "皮革帽子", "铁头盔", 1);
        assertTrue(s.contains("皮革帽子") && s.contains("铁头盔"));
        assertTrue(s.contains("更好的"));
    }

    @Test
    void 没有更好护甲就不说话() {
        assertEquals("", RddNeedsText.gear("胸甲", "钻石胸甲", "", 0));
        assertEquals("", RddNeedsText.gear("胸甲", "", "  ", 1));
    }

    @Test
    void 饿了有吃的提示先吃() {
        String s = RddNeedsText.food(10, 27);
        assertTrue(s.contains("<food_hint>"));
        assertTrue(s.contains("10/20"));
        assertTrue(s.contains("27"));
        assertTrue(s.contains("eat"));
    }

    @Test
    void 饿了没吃的提示去弄吃的() {
        String s = RddNeedsText.food(6, 0);
        assertTrue(s.contains("没有能直接吃的东西"), "没吃的必须直说，别让她白找");
        assertTrue(s.contains("很饿"), "6 点属于很饿");
    }

    @Test
    void 不饿就不说话() {
        // 2026-10-28 改口径：**不饿但背包零食物**要提示（"趁现在补"），
        // 因为实机她在下界 hunger=20 却一份吃的都没有，而下界没有农田和树。
        assertNotEquals("", RddNeedsText.food(20, 0), "不饿但零食物也要提示：趁现在补");
        assertNotEquals("", RddNeedsText.food(RddNeedsText.HUNGRY_AT, 0), "阈值上边界、零食物 → 提示补货");
        assertEquals("", RddNeedsText.food(20, 3), "不饿且身上有吃的 → 安静");
        assertNotEquals("", RddNeedsText.food(RddNeedsText.HUNGRY_AT - 1, 1), "阈值下一格要提示");
    }
}
