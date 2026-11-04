package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 037/T3 第二轮：成就条件修复预算。
 *
 * <p>为什么要有它：INVALID 每 tick 都判得出来，不设上限就会每 tick 请求一次重规划 ——
 * 把"无限重试动作"换成"无限重试规划"。预算用尽必须可见（报 exhausted），不能悄悄重试。
 */
class AdvancementRepairBudgetTest {

    @Test
    void defaultBudgetIsOneRepairPerSubtask() {
        AdvancementRepairBudget budget = new AdvancementRepairBudget();
        assertEquals(1, budget.maxAttempts(), "候选值必须显式可读，不许藏在调用点");
        assertEquals(AdvancementRepairBudget.Decision.REQUEST, budget.take("step-a"));
        assertEquals(AdvancementRepairBudget.Decision.EXHAUSTED, budget.take("step-a"),
                "第二次必须拒绝，否则就是无限悄悄重试");
        assertEquals(1, budget.used("step-a"));
    }

    @Test
    void budgetIsPerSubtaskNotGlobal() {
        AdvancementRepairBudget budget = new AdvancementRepairBudget();
        assertEquals(AdvancementRepairBudget.Decision.REQUEST, budget.take("step-a"));
        assertEquals(AdvancementRepairBudget.Decision.REQUEST, budget.take("step-b"),
                "另一个二级有自己的预算");
    }

    @Test
    void completedSubtaskReleasesItsBudget() {
        AdvancementRepairBudget budget = new AdvancementRepairBudget();
        budget.take("step-a");
        assertEquals(AdvancementRepairBudget.Decision.EXHAUSTED, budget.take("step-a"));
        budget.forget("step-a");
        assertEquals(AdvancementRepairBudget.Decision.REQUEST, budget.take("step-a"),
                "同 id 复用时不该误判已用尽");
    }

    @Test
    void blankIdsNeverConsumeBudget() {
        AdvancementRepairBudget budget = new AdvancementRepairBudget();
        assertEquals(AdvancementRepairBudget.Decision.EXHAUSTED, budget.take(""));
        assertEquals(AdvancementRepairBudget.Decision.EXHAUSTED, budget.take(null));
        assertEquals(0, budget.used(""));
    }

    @Test
    void configurableBudgetIsHonoured() {
        AdvancementRepairBudget budget = new AdvancementRepairBudget(3);
        assertEquals(AdvancementRepairBudget.Decision.REQUEST, budget.take("s"));
        assertEquals(AdvancementRepairBudget.Decision.REQUEST, budget.take("s"));
        assertEquals(AdvancementRepairBudget.Decision.REQUEST, budget.take("s"));
        assertEquals(AdvancementRepairBudget.Decision.EXHAUSTED, budget.take("s"));
        assertTrue(budget.maxAttempts() == 3);
    }

    @Test
    void trackingStaysBounded() {
        AdvancementRepairBudget budget = new AdvancementRepairBudget();
        for (int i = 0; i < 400; i++) {
            budget.take("step-" + i);
        }
        // 不会因为链很长而无限增长：早期 ID 的账被淘汰，最新的还在
        assertEquals(1, budget.used("step-399"));
        assertEquals(0, budget.used("step-0"));
    }
}
