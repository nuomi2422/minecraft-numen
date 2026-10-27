package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * D-01/D-02 的护栏：危险事实块（纯函数，不碰 Minecraft）。
 *
 * <p>证据来源：`exp-session-stats` 扫最近 3 天 52 次死亡，死前一刻在做什么 ——
 * 4 次在查背包/查任务状态/找位置，2 次在吃东西。上下文里没有「你正被围着」这条事实。
 *
 * <p>本类钉四件事：
 * <ol>
 *   <li>没怪 → 空串（不占 token、不制造假警报）</li>
 *   <li>远处有怪但血满 → 空串（不是"此刻的麻烦"）</li>
 *   <li>贴身有怪或已经掉血 → 必须出现，且带数量与血量</li>
 *   <li>措辞必须是「事实」不是「指令」（本仓的执行侧纪律：动作归执行 AI）</li>
 * </ol>
 */
class RddDangerFactsTest {

    @Test
    void 没有敌怪时为空串() {
        assertEquals("", RddDangerText.block(0, 0, 100, ""));
        assertEquals("", RddDangerText.block(0, 0, 10, "zombie"), "没怪就是没危险，血量低也不是这个块的职责");
    }

    @Test
    void 远处有怪但血满时不出现() {
        String b = RddDangerText.block(2, 0, 100, "zombie");
        assertEquals("", b, "16 格外的怪 + 满血 = 不是此刻的麻烦，弹出来只会变成噪音");
    }

    @Test
    void 贴身有怪时出现且带距离与数量() {
        String b = RddDangerText.block(3, 1, 100, "skeleton");
        assertTrue(b.startsWith("<danger>") && b.endsWith("</danger>"), "必须是独立块，便于上下文定位");
        assertTrue(b.contains("3 只敌怪"), "总数要写明：" + b);
        assertTrue(b.contains("1 只在 8 格内"), "贴身数要写明：" + b);
        assertTrue(b.contains("skeleton"), "种类要写明（骷髅和僵尸的处理不一样）：" + b);
    }

    @Test
    void 掉血时即使怪都远也要出现() {
        String b = RddDangerText.block(1, 0, 40, "creeper");
        assertTrue(b.contains("血量 40%"), "掉血本身就是事实：" + b);
        assertTrue(b.contains("已经受伤"), "要说清它是受伤状态，不是满血在挨打：" + b);
    }

    @Test
    void 是事实不是指令() {
        String b = RddDangerText.block(2, 2, 30, "zombie");
        assertTrue(b.contains("这是事实，不是指令"), "执行侧纪律：给事实，不给动作命令");
        assertFalse(b.contains("快跑") || b.contains("必须"), "不许写命令式：动作归执行 AI 决定");
    }

    @Test
    void 血量百分比被夹在合法范围() {
        assertTrue(RddDangerText.block(1, 1, 250, "x").contains("血量 100%"), "超过 100 要夹住，不许写出 250%");
        assertTrue(RddDangerText.block(1, 1, -5, "x").contains("血量 0%"), "负数要夹住");
    }

    @Test
    void 长度有界() {
        String many = "a、b、c、d、e、f、g、h";
        String b = RddDangerText.block(99, 99, 5, many);
        assertTrue(b.length() < 260, "上下文块必须有界（实际 " + b.length() + " 字）");
    }
}
