package com.dwinovo.numen.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
