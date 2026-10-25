package com.dwinovo.numen.plugins.learner.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LC5（78 号 §4/§7 判据）：「真触发＋相似无危险反例＋冷却＋停止；
 * 一事件仅建候选，新规则未批准不派动作」。
 */
class DefenseRuleDraftTest {

    private static DefenseRuleDraft nightLavaRule() {
        return new DefenseRuleDraft("def-lava-night-1", "夜间靠近岩浆时保持距离",
                List.of("仅夜间", "岩浆半径3格内"), 60_000L);
    }

    @Test
    void 新草案起点是影子_不派动作() {
        DefenseRuleDraft r = nightLavaRule();
        assertEquals(DefenseRuleDraft.State.SHADOW, r.state());
        // 影子期评估任何强事件都只是记录——wouldTrigger 的结果不产生动作（状态机没有动作面）
        DefenseRuleDraft.ShadowVerdict v = r.observeShadow(true, true, 1000L, "夜间岩浆边");
        assertTrue(v.wouldTrigger());
        assertEquals(1, v.truePositives());
        assertEquals(DefenseRuleDraft.State.SHADOW, r.state(), "真触发后仍在影子——不因此派动作/提权");
    }

    @Test
    void 影子达标也不自动启用_必须显式批准() {
        DefenseRuleDraft r = nightLavaRule();
        r.observeShadow(true, true, 1_000_000L, "夜1");
        r.observeShadow(true, true, 1_000_000L + 61_000L, "夜2（冷却60s外）");
        assertTrue(r.shadowQualifies(), "2 次真触发 0 误触发应达标");
        assertEquals(DefenseRuleDraft.State.SHADOW, r.state(),
                "★ 78号§4「不因一次危险就提高权限」——机器数据再好也不自动 ACTIVE");

        assertFalse(r.approve("  "), "空理由不许批准");
        assertTrue(r.approve("影子期2次真触发0误报，限域夜间+岩浆3格"),
                "达标+带理由才放行");
        assertEquals(DefenseRuleDraft.State.ACTIVE, r.state());
    }

    @Test
    void 相似无危险反例阻止启用() {
        DefenseRuleDraft r = nightLavaRule();
        r.observeShadow(true, true, 1_000_000L, "夜1");
        r.observeShadow(true, true, 1_000_000L + 61_000L, "夜2（冷却60s外）");
        // 白天平地误触发——78 号判据「相似无危险反例」（也在冷却外）
        DefenseRuleDraft.ShadowVerdict fp = r.observeShadow(false, true, 1_000_000L + 122_000L, "白天平地");
        assertTrue(fp.wouldTrigger());
        assertEquals(1, fp.falsePositives());
        assertFalse(r.shadowQualifies(), "有误触发不许启用");
        assertFalse(r.approve("数据没达标想强批"), "未达标时显式批准也被拒");
    }

    @Test
    void 冷却命中不增计数() {
        DefenseRuleDraft r = nightLavaRule();
        r.observeShadow(true, true, 1_000_000L, "第一次");
        DefenseRuleDraft.ShadowVerdict cd = r.observeShadow(true, true, 1_000_100L, "100ms内重复");
        assertFalse(cd.wouldTrigger(), "冷却期内不触发");
        assertEquals(1, cd.truePositives(), "冷却命中不增真触发计数");
        assertEquals(1, cd.cooldownHits());
        // 冷却过后恢复计数
        DefenseRuleDraft.ShadowVerdict after = r.observeShadow(true, true, 1_000_000L + 60_001L, "冷却后");
        assertTrue(after.wouldTrigger());
        assertEquals(2, after.truePositives());
    }

    @Test
    void 错误触发立即可停_停过的回影子重新验() {
        DefenseRuleDraft r = nightLavaRule();
        r.observeShadow(true, true, 1000L, "夜1");
        r.observeShadow(true, true, 2000L, "夜2");
        r.approve("达标批准");
        // ACTIVE 也可立即停（无需审查）
        assertTrue(r.suspend(), "★ 刹车权高于一切——ACTIVE 说停就停");
        assertEquals(DefenseRuleDraft.State.SUSPENDED, r.state());
        // 恢复不直接回 ACTIVE——重新走影子
        assertTrue(r.resumeAsShadow());
        assertEquals(DefenseRuleDraft.State.SHADOW, r.state());
        assertEquals(0, r.truePositives(), "回影子时影子数据清零重新验");
    }

    @Test
    void 漏触发如实记录_不误报() {
        DefenseRuleDraft r = nightLavaRule();
        // 危险场景但规则条件没命中（漏）——事实留但不计误触发
        DefenseRuleDraft.ShadowVerdict miss = r.observeShadow(true, false, 1000L, "夜间岩浆边但条件漏");
        assertFalse(miss.wouldTrigger());
        assertEquals(0, miss.falsePositives(), "漏触发不是误触发——两者语义相反不许混");
        assertEquals(0, miss.truePositives());
    }

    @Test
    void 撤回是终态() {
        DefenseRuleDraft r = nightLavaRule();
        assertTrue(r.retire());
        assertEquals(DefenseRuleDraft.State.RETIRED, r.state());
        assertFalse(r.suspend(), "终态不可再迁移");
        assertFalse(r.resumeAsShadow());
        assertFalse(r.approve("终态后想批"));
        // RETIRED 后观察也不收
        DefenseRuleDraft.ShadowVerdict v = r.observeShadow(true, true, 1000L, "x");
        assertFalse(v.wouldTrigger());
        assertTrue(v.reason().contains("RETIRED"));
    }

    @Test
    void 一次强事件只建一个候选_状态机无动作面() {
        // 78号判据「一事件仅建候选，新规则未批准不派动作」——本类的 API 表面上
        // 没有任何 execute/dispatch/act 方法，这是结构性的保证：编译期就不存在派动作的路径。
        // 这条测试钉的是读数形状：观察再多，state 也只在 SHADOW/ACTIVE/SUSPENDED/RETIRED 里。
        DefenseRuleDraft r = nightLavaRule();
        for (int i = 0; i < 10; i++) {
            r.observeShadow(true, true, 1000L * (i + 1), "风暴" + i);
        }
        assertEquals(DefenseRuleDraft.State.SHADOW, r.state());
        assertTrue(r.readout().containsKey("note"));
        assertEquals("def-lava-night-1", r.readout().get("rule_id"));
    }
}
