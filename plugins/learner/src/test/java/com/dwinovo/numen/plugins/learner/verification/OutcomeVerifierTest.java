package com.dwinovo.numen.plugins.learner.verification;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分型验证：每类产物用自己的信号，不清楚就说不清楚（159 I）。
 */
class OutcomeVerifierTest {

    private static Map<String, Object> world(Object hp, Object food) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hp", hp);
        m.put("food", food);
        return m;
    }

    private static BeforeAfter.Diff diff(Object hpBefore, Object hpAfter) {
        return BeforeAfter.diff(world(hpBefore, 20), world(hpAfter, 20));
    }

    // ---------------- 自动动作

    @Test
    void 没触发过是没发生不是没效果() {
        var r = OutcomeVerifier.forAutoAction(new OutcomeVerifier.AutoActionFacts(0, diff(3, 3), 5));
        assertEquals(OutcomeVerdict.INCONCLUSIVE, r.verdict());
        assertEquals(OutcomeVerdict.NEVER_TRIGGERED, r.code());
    }

    @Test
    void 缺快照是比不了不是没变化() {
        var r = OutcomeVerifier.forAutoAction(new OutcomeVerifier.AutoActionFacts(
                2, BeforeAfter.diff(null, world(5, 20)), 5));
        assertEquals(OutcomeVerdict.INCONCLUSIVE, r.verdict());
        assertEquals(OutcomeVerdict.SNAPSHOT_MISSING, r.code());
    }

    @Test
    void 字段缺失也是比不了() {
        Map<String, Object> before = world(3, 20);
        Map<String, Object> after = world(5, null);
        var d = BeforeAfter.diff(before, after);
        assertFalse(d.missing().isEmpty());

        var r = OutcomeVerifier.forAutoAction(new OutcomeVerifier.AutoActionFacts(2, d, 10));
        assertEquals(OutcomeVerdict.INCONCLUSIVE, r.verdict(),
                "缺字段当「没变化」会把「没采到」读成「没发生」");
        assertEquals(OutcomeVerdict.SNAPSHOT_MISSING, r.code());
    }

    @Test
    void 世界变了算支持但不等于变好() {
        var r = OutcomeVerifier.forAutoAction(new OutcomeVerifier.AutoActionFacts(2, diff(3, 5), 0));
        assertEquals(OutcomeVerdict.SUPPORTED, r.verdict());
        assertEquals(OutcomeVerdict.WORLD_CHANGED, r.code());
        assertTrue(r.detail().contains("变了 ≠ 变好"), "措辞必须挡住「世界变了=规则有效」的误读");
    }

    @Test
    void 无变化且基线不足是INCONCLUSIVE() {
        var r = OutcomeVerifier.forAutoAction(new OutcomeVerifier.AutoActionFacts(2, diff(3, 3), 2));
        assertEquals(OutcomeVerdict.INCONCLUSIVE, r.verdict());
        assertEquals(OutcomeVerdict.TOO_FEW_SAMPLES, r.code());
    }

    @Test
    void 无变化且基线足够才判反例() {
        var r = OutcomeVerifier.forAutoAction(new OutcomeVerifier.AutoActionFacts(2, diff(3, 3), 3));
        assertEquals(OutcomeVerdict.CONTRADICTED, r.verdict());
        assertEquals(OutcomeVerdict.NO_EFFECT, r.code());
    }

    // ---------------- 经验

    @Test
    void 经验没有基线就是样本不足() {
        var r = OutcomeVerifier.forExperience(new OutcomeVerifier.ExperienceFacts(0, true, true, true));
        assertEquals(OutcomeVerdict.INCONCLUSIVE, r.verdict());
        assertEquals(OutcomeVerdict.TOO_FEW_SAMPLES, r.code());
    }

    @Test
    void 跨同伴基线不可比() {
        var r = OutcomeVerifier.forExperience(new OutcomeVerifier.ExperienceFacts(5, false, true, true));
        assertEquals(OutcomeVerdict.INCONCLUSIVE, r.verdict());
        assertEquals(OutcomeVerdict.INCOMPARABLE_ENVIRONMENT, r.code());
    }

    @Test
    void 目标不同算替代解释() {
        var r = OutcomeVerifier.forExperience(new OutcomeVerifier.ExperienceFacts(5, true, false, true));
        assertEquals(OutcomeVerdict.INCONCLUSIVE, r.verdict());
        assertEquals(OutcomeVerdict.ALTERNATIVE_EXPLANATION, r.code());
    }

    @Test
    void 同执行者同目标环境可比才支持() {
        var r = OutcomeVerifier.forExperience(new OutcomeVerifier.ExperienceFacts(4, true, true, true));
        assertEquals(OutcomeVerdict.SUPPORTED, r.verdict());
        assertTrue(r.conclusive());
    }

    // ---------------- 支线

    @Test
    void 支线被拒绝不算失败() {
        var r = OutcomeVerifier.forSideTask(false, 0, 3);
        assertEquals(OutcomeVerdict.INCONCLUSIVE, r.verdict(), "不安全的任务本就该拒，拒不是失败");
        assertEquals("not_accepted", r.code());
    }

    @Test
    void 支线中途停下是INCONCLUSIVE不是失败() {
        var r = OutcomeVerifier.forSideTask(true, 1, 3);
        assertEquals(OutcomeVerdict.INCONCLUSIVE, r.verdict());
        assertEquals("stalled", r.code());
        assertTrue(r.detail().contains("中途≠失败"));
    }

    @Test
    void 支线走到完成条件是支持() {
        assertEquals(OutcomeVerdict.SUPPORTED, OutcomeVerifier.forSideTask(true, 3, 3).verdict());
        assertEquals(OutcomeVerdict.SUPPORTED, OutcomeVerifier.forSideTask(true, 4, 3).verdict());
    }

    // ---------------- 对比本身

    @Test
    void 对比能分辨数值类型差异() {
        assertTrue(BeforeAfter.diff(world(3, 20), world(5, 20)).changed().contains("hp"));
        assertTrue(BeforeAfter.diff(world(3, 20), world(3L, 20)).changed().isEmpty(),
                "JSON 里 3 与 3L 是同一个数，不许算变化");
    }

    @Test
    void 对比能报新增与删除() {
        Map<String, Object> before = world(3, 20);
        Map<String, Object> after = world(3, 20);
        after.put("armor", 5);
        var d = BeforeAfter.diff(before, after);
        assertTrue(d.added().contains("armor"));
        assertTrue(BeforeAfter.diff(after, before).removed().contains("armor"));
    }
}
