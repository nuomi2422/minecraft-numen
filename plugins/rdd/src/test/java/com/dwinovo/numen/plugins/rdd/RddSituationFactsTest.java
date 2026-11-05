package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** A3（95 号）：四层事实的纯分类逻辑。 */
class RddSituationFactsTest {

    @Test void waterAndLowHpAreFactLayer() {
        List<String> kinds = RddSituationFacts.classify(
                new RddSituationFacts.Snapshot(true, false, 5.0f, 20, 0));
        assertTrue(kinds.contains("fact:in_water"));
        assertTrue(kinds.contains("fact:low_hp"));
        assertFalse(kinds.contains("progress:stalled"), "刚站定不算停滞");
        for (String k : kinds) {
            assertTrue(k.startsWith("fact:") || k.startsWith("progress:"), "层前缀必须显式: " + k);
        }
    }

    @Test void lavaWinsOverWater() {
        List<String> kinds = RddSituationFacts.classify(
                new RddSituationFacts.Snapshot(true, true, 20f, 20, 0));
        assertTrue(kinds.contains("fact:in_lava"));
        assertFalse(kinds.contains("fact:in_water"), "岩浆里不再重复报水");
    }

    @Test void stallNeedsSixtySecondsOfNoMove() {
        assertFalse(RddSituationFacts.classify(
                new RddSituationFacts.Snapshot(false, false, 20f, 20, 59 * 20L)).contains("progress:stalled"));
        assertTrue(RddSituationFacts.classify(
                new RddSituationFacts.Snapshot(false, false, 20f, 20, 60 * 20L)).contains("progress:stalled"));
    }

    @Test void noFoodIsFactNotJudgement() {
        List<String> kinds = RddSituationFacts.classify(
                new RddSituationFacts.Snapshot(false, false, 20f, 4, 0));
        assertTrue(kinds.contains("fact:no_food"));
        assertFalse(kinds.contains("fact:low_hp"), "饱食度低不等于血量低");
    }

    @Test void healthyDryStandingStillIsEmpty() {
        assertTrue(RddSituationFacts.classify(
                new RddSituationFacts.Snapshot(false, false, 20f, 20, 10 * 20L)).isEmpty());
    }

    @Test void nullSnapshotIsEmpty() {
        assertTrue(RddSituationFacts.classify(null).isEmpty());
    }
    /**
     * 2026-11-05：链已 COMPLETED、同伴在等主人时，站着不动不是停滞。
     * 实测 96 条 progress:stalled 里 88 条发生在同一坐标、同一停车状态。
     */
    @Test void idleIsNotStall() {
        RddSituationFacts.Snapshot still = new RddSituationFacts.Snapshot(false, false, 20f, 20, 600 * 20L);
        assertTrue(RddSituationFacts.classify(still, false).isEmpty(), "没活可干时不该报停滞：" + RddSituationFacts.classify(still, false));
        assertTrue(RddSituationFacts.classify(still, true).contains("progress:stalled"), "有活在推进、又不动，才算停滞");
    }
}
