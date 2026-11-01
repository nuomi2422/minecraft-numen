package com.dwinovo.numen.rdd.policy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 167 19.4: the input already said "iron pickaxe cannot mine obsidian", yet the new plan mined it anyway. */
class MeasuredFactsTest {

    private static final String COUNTER =
            "失败：[NEGOTIATION] soldier COUNTER: 实测确认铁镐挖不动黑曜石：我放了一块自己的黑曜石做无损检测，"
            + "手持铁镐时 inspect_block 返回 needs_correct_tool=true";

    @Test
    void extractsTheObsidianFactFromTheRealCounter() {
        var facts = MeasuredFacts.extract(COUNTER);
        assertTrue(facts.stream().anyMatch(f -> f.item().equals("黑曜石")), facts.toString());
        assertTrue(facts.stream().anyMatch(f -> f.tool().contains("镐")), facts.toString());
    }

    @Test
    void flagsTheExactBadSubtask() {
        var facts = MeasuredFacts.extract(COUNTER);
        var subs = List.of(new PlanConsistency.Subtask(
                "补足黑曜石：背包已有 7 个，还差 3 个。用现有铁镐去挖黑曜石（黑曜石只在岩浆湖边生成）",
                Map.of("asset_key", "minecraft:obsidian", "minimum", 10)));
        var v = MeasuredFacts.violations(facts, subs);
        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("黑曜石"), v.toString());
    }

    @Test
    void nonMiningStepsAreNotFlagged() {
        var facts = MeasuredFacts.extract(COUNTER);
        var subs = List.of(new PlanConsistency.Subtask(
                "找现成的废墟传送门，框体现成黑曜石通常只差 1-4 块", Map.of()));
        assertEquals(List.of(), MeasuredFacts.violations(facts, subs));
    }

    @Test
    void emptyOrUnrelatedTextIsSafe() {
        assertEquals(List.of(), MeasuredFacts.extract(null));
        assertEquals(List.of(), MeasuredFacts.extract("今天天气不错"));
        assertEquals(List.of(), MeasuredFacts.violations(List.of(), List.of()));
    }
}