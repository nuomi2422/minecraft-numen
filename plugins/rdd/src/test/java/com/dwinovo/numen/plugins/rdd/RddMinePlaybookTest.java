package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 下矿姿势提示的护栏（纯文本）。
 *
 * <p>三条纪律要钉住：
 * <ol>
 *   <li>只在谈下矿时出现（普通目标零注入）；</li>
 *   <li>给的是顺序与判据，不写死数量/坐标（实况会打脸，也会盖过经验库路线）；</li>
 *   <li>四个关键动作必须在（水桶/垫脚/别垂直挖/夜里不下矿），因为它们各自对应一类死亡。</li>
 * </ol>
 */
class RddMinePlaybookTest {

    @Test
    void 无关目标不注入() {
        assertEquals("", RddMinePlaybook.block("造一个箱子"));
        assertEquals("", RddMinePlaybook.block("去睡觉"));
        assertEquals("", RddMinePlaybook.block(""));
        assertEquals("", RddMinePlaybook.block(null));
    }

    @Test
    void 谈下矿时注入() {
        for (String t : new String[]{"挖矿", "下矿洞", "找钻石", "挖铁", "深层探险"}) {
            String b = RddMinePlaybook.block(t);
            assertFalse(b.isBlank(), "「" + t + "」应命中下矿提示");
            assertTrue(b.contains("<mine_playbook>") && b.contains("</mine_playbook>"));
        }
    }

    @Test
    void 四个关键动作都在() {
        String b = RddMinePlaybook.block("挖矿");
        assertTrue(b.contains("水桶"), "岩浆是溺水/烧死的来源，必须提水桶");
        assertTrue(b.contains("垫脚"), "垂直落差靠垫脚，别直接跳");
        assertTrue(b.contains("垂直往下挖"), "垂直挖穿是摔死/窒息主因");
        assertTrue(b.contains("夜里不下矿"), "41 次死亡死在怪手里，洞里遇怪没有退路");
        assertTrue(b.contains("火把"), "插火把既防刷怪又认路");
    }

    @Test
    void 不写死数量与坐标() {
        String b = RddMinePlaybook.block("挖矿");
        assertFalse(b.matches(".*挖\\s*\\d+\\s*个.*"), "不许写死数量：" + b);
        assertFalse(b.matches(".*\\(\\s*-?\\d+\\s*,\\s*-?\\d+.*"), "不许写坐标");
    }

    @Test
    void 有界() {
        String b = RddMinePlaybook.block("挖矿");
        assertTrue(b.length() < 900, "上下文块必须有界（实际 " + b.length() + " 字）");
    }
}
