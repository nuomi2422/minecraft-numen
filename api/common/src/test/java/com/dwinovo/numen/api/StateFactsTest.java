package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-03（外部学习者 #475/#481）：把 hp/掉血/夜间/敌对/床 这些硬安全事实前置成一行。
 * 判据：能读到就写、读不到就不写（不造假），没有任何事实时整行不出现。
 */
class StateFactsTest {

    @Test
    void extractsTheFactsTheLowHpCaseNeeds() {
        String state = "<rdd>...</rdd><alarms scope=\"nearby16\"><alarm rule=\"night\" facts=\"night=1 time=19342 bed=0 hostile=true\">天黑了</alarm></alarms>"
                + "<env> 血量5 · **正在持续掉血：先撤到安全处** · 手上 cooked_beef </env>";
        String facts = NumenPlugins.stateFactsOf(state);
        assertTrue(facts.startsWith("<state_facts"), facts);
        assertTrue(facts.contains("hp=5"), facts);
        assertTrue(facts.contains("dmg=1"), facts);
        assertTrue(facts.contains("night=1"), facts);
        assertTrue(facts.contains("hostile=1"), facts);
        assertTrue(facts.contains("bed=0"), facts);
    }

    @Test
    void noFactsMeansNoLine() {
        assertEquals("", NumenPlugins.stateFactsOf(""));
        assertEquals("", NumenPlugins.stateFactsOf("<rdd><enabled>true</enabled></rdd>"));
    }

    @Test
    void missingFactsAreOmittedNotFaked() {
        String facts = NumenPlugins.stateFactsOf("<env> 血量 12 · 危险 最近受伤无记录 </env>");
        assertTrue(facts.contains("hp=12"), facts);
        assertTrue(!facts.contains("dmg="), "没有持续掉血就不许写 dmg：" + facts);
        assertTrue(!facts.contains("night="), "没有夜间告警就不许写 night：" + facts);
    }

    /** 白天也要能表达：只有 night=1 时，"白天适用"的条目永远匹配不上。 */
    @Test
    void daytimeIsExpressedAsNightEqualsZero() {
        String facts = NumenPlugins.stateFactsOf("<carry> … night=0 hostile=false …</carry>");
        assertTrue(facts.contains("night=0"), facts);
        assertTrue(facts.contains("hostile=0"), facts);
        String night = NumenPlugins.stateFactsOf("<carry> … night=1 …</carry>");
        assertTrue(night.contains("night=1"), night);
    }
    @Test void historicalTaskHpCannotOverrideCurrentEnvironmentHealth() {
        String state = "<rdd><current_task>旧任务：血量3.2，先撤离</current_task>"
                + "<why>上次血量2，不代表现在</why><env>\n身体 血量17.226 · 饥饿20\n</env></rdd>";
        String facts = NumenPlugins.stateFactsOf(state);
        assertTrue(facts.contains("hp=17.226"), facts);
        assertFalse(facts.contains("hp=3.2"), facts);
    }

    @Test void unknownEnvironmentCannotBorrowHpFromTasksReasonsOrCarryText() {
        String state = "<rdd><current_task>历史血量3.2</current_task><why>血量2</why>"
                + "<carry>上次血量1 night=0 hostile=false</carry>"
                + "<env>自身观测不可用；血量未知</env></rdd>";
        String facts = NumenPlugins.stateFactsOf(state);
        assertFalse(facts.contains("hp="), facts);
        assertTrue(facts.contains("night=0"), facts);
        assertTrue(facts.contains("hostile=0"), facts);
    }

    @Test void mandatoryRuntimeFragmentStillSeparatesPlanProseFromEnvironmentHp() {
        var fragments = java.util.List.of(
                new StateFragment("rdd", "runtime", "<rdd><current_task>历史血量3.2</current_task>"
                        + "<env>身体 血量17.226 · 饥饿20</env></rdd>", true, 100),
                new StateFragment("rdd", "guide", "<env>血量1</env>", false, 5));
        assertEquals("<state_facts hp=17.226/>\n", NumenPlugins.stateFactsOf(fragments));
    }

    @Test void incidentSiblingEnvironmentWinsAndRealLowHealthRemainsLow() {
        assertEquals("<state_facts hp=17.226276/>\n", NumenPlugins.stateFactsOf(
                "<rdd><current_task>生存闸：血量 3.2，旧任务</current_task></rdd>"
                        + "<env>身体 血量17.226276 · 饥饿17</env>"));
        assertEquals("<state_facts hp=3.2/>\n", NumenPlugins.stateFactsOf(
                "<current_task>旧血量18</current_task><env>身体 血量3.2 · 饥饿17</env>"));
        assertFalse(NumenPlugins.stateFactsOf("<current_task>血量3.2</current_task>").contains("hp="));
    }

    @Test void incompleteSplitOptionalAndConflictingEnvironmentsCannotInventHealth() {
        for (String state : java.util.List.of("<env>血量3.2", "血量3.2</env>",
                "<env><why>血量3.2</why></env>", "<env>血量18</env><env>血量3.2</env>",
                "<env>血量未知</env><env>血量18</env>", "<env>血量3.2<env>血量18</env>"))
            assertFalse(NumenPlugins.stateFactsOf(state).contains("hp="), state);
        assertFalse(NumenPlugins.stateFactsOf(java.util.List.of(
                new StateFragment("rdd","first","<env>血量",true,100),
                new StateFragment("rdd","second","3.2</env>",true,100))).contains("hp="));
        assertFalse(NumenPlugins.stateFactsOf(java.util.List.of(
                new StateFragment("rdd","task","<current_task>血量3.2</current_task>",true,100),
                new StateFragment("other","guide","<env>血量18</env>",false,1))).contains("hp="));
        assertEquals("<state_facts hp=3.2/>\n",NumenPlugins.stateFactsOf("<env>血量3.2</env><env>血量3.2</env>"));
    }

    @Test void percentDangerTextIsNotAbsoluteHp() {
        // 2026-11-06: RddDangerText 的“血量87%”是比例，不得提取为 hp=87（也不得回溯成 8）。
        assertFalse(NumenPlugins.stateFactsOf("<env> 血量87% </env>").contains("hp="));
        assertFalse(NumenPlugins.stateFactsOf("<env> 血量87％ </env>").contains("hp="));
        assertFalse(NumenPlugins.stateFactsOf("<env> 血量87 % </env>").contains("hp="));
        assertTrue(NumenPlugins.stateFactsOf("<env> 血量7.5/20 </env>").contains("hp=7.5"));
        assertTrue(NumenPlugins.stateFactsOf("<env> 血量 12 </env>").contains("hp=12"));
    }

}
