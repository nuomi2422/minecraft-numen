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
    void 不饿但随身不足十份时给软储备建议_旧静默断言被新需求替代() {
        // 2026-11-15 B2: 旧行为“健康随身3份→静默”被主人明确的 10 份软储备契约替代。
        // 旧断言保留在 git 历史中；这里改为要求同一 food_hint 给出建议，且不抢任务。
        String s = RddNeedsText.food(20, 3);
        assertTrue(s.contains("<food_hint>"), "健康但 <10 份要提示（非紧急）");
        assertEquals(1, s.split("<food_hint>", -1).length - 1, "只允许一个 food_hint");
        assertTrue(s.contains("合计 10 份"), "建议目标是 10 份，不是紧急 2 份");
        assertTrue(s.contains("随身已观测 3 份"));
        assertTrue(s.contains("UNKNOWN"), "备用未知必须明说");
        assertFalse(s.contains("备用 0 份"), "未知不能写成 0");
        assertEquals("", RddNeedsText.food(20, 10), "随身 10 份已够，不再提示");
        assertEquals("", RddNeedsText.food(20, 27), "随身富余 → 安静");
    }

    @Test
    void 零库存与饥饿分支也带十份建议且不重复() {
        String zero = RddNeedsText.food(20, 0);
        assertTrue(zero.contains("一份能吃的都没有"));
        assertTrue(zero.contains("合计 10 份"));
        assertEquals(1, zero.split("<food_hint>", -1).length - 1);
        String hungry = RddNeedsText.food(11, 3);
        assertTrue(hungry.contains("eat"), "饿了先吃，建议不变");
        assertTrue(hungry.contains("合计 10 份"));
        assertEquals(1, hungry.split("<food_hint>", -1).length - 1);
    }

    @Test
    void 建议文本声明不抢主线且不假定补给可达() {
        String s = RddNeedsText.food(20, 9);
        assertTrue(s.contains("不抢当前任务"), "软建议不能改变 owner/当前任务");
        assertTrue(s.contains("不另拉路线"), "主线若在补给不另拉；不假定村庄词=可达");
        assertFalse(s.contains("村庄"), "不得用村庄关键词猜补给路线");
    }
    @Test
    void 未知随身数量只报UNKNOWN不冒无食物不建议eat() {
        String s = RddNeedsText.food(20, -1);
        assertTrue(s.contains("UNKNOWN"), "未知数量必须明说 UNKNOWN");
        assertTrue(s.contains("不按 0"));
        assertFalse(s.contains("一份能吃的都没有"), "未知不能冒称没有");
        assertFalse(s.contains("eat"), "未知不建议吃");
    }

    @Test
    void 未知饱食度不冒充很饿且仍可给储备建议() {
        String s = RddNeedsText.food(-1, 3);
        assertTrue(s.contains("饱食度未知"), "未知饱食度要说 UNKNOWN");
        assertFalse(s.contains("-1/20"), "不得写 -1/20");
        assertFalse(s.contains("很饿"));
        assertTrue(s.contains("合计 10 份"), "储备建议与饥饿判断分开");
        assertTrue(s.contains("随身已观测 3 份"));
    }
    @Test
    void 饥饿危急分支不追加十份储备目标() {
        String starving = RddNeedsText.food(3, 0);
        assertTrue(starving.contains("没有能直接吃的东西"), "危急仍给原找食物句");
        assertFalse(starving.contains("合计 10 份"), "危急不把 2 份目标拖成 10 份");
        String low = RddNeedsText.food(0, 2);
        assertTrue(low.contains("很饿"));
        assertFalse(low.contains("合计 10 份"));
    }

    @Test
    void 普通饥饿仍有eat并给软十份建议() {
        String s = RddNeedsText.food(11, 3);
        assertTrue(s.contains("eat"));
        assertTrue(s.contains("合计 10 份"), "非危急才出现长期软建议");
    }
}
