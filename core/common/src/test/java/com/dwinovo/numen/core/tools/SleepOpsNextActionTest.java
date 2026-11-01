package com.dwinovo.numen.core.tools;

import net.minecraft.world.entity.player.Player;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 167 §B4-5：睡眠被拒时，必须给**可执行动作**，不能只报原因。
 *
 * <p>这条规则的价值在于"不许原地重试"：原版原因（太远/有怪）本身不会告诉模型下一步，
 * 于是它会把 sleep 再调一遍。这里把每种拒绝映射成一个动作词，并保证没有漏项。
 */
class SleepOpsNextActionTest {

    @Test
    void everyVanillaRefusalMapsToAnAction() {
        for (Player.BedSleepingProblem p : Player.BedSleepingProblem.values()) {
            String action = SleepOps.nextAction(p);
            assertFalse(action == null || action.isBlank(), p + " 没有对应动作");
            assertFalse(SleepOps.nextActionHint(p).isBlank(), p + " 没有对应提示");
        }
    }

    @Test
    void distanceAndDangerMapToDifferentActions() {
        assertEquals("walk_to_bed", SleepOps.nextAction(Player.BedSleepingProblem.TOO_FAR_AWAY));
        assertEquals("clear_monsters", SleepOps.nextAction(Player.BedSleepingProblem.NOT_SAFE));
        assertEquals("defer_until_night", SleepOps.nextAction(Player.BedSleepingProblem.NOT_POSSIBLE_NOW));
    }

    @Test
    void nullIsTolerated() {
        assertEquals("", SleepOps.nextAction(null));
        assertEquals("", SleepOps.nextActionHint(null));
    }
}
