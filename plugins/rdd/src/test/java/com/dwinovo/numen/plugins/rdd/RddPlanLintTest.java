package com.dwinovo.numen.plugins.rdd;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RddPlanLintTest {

    @Test void coordinateMismatchIsFlagged() {
        var warn = RddPlanLint.check("把东西放进 360,65,636 的箱子", 360, 66, 636, "container_at", "minecraft:shield");
        assertTrue(warn.stream().anyMatch(w -> w.rule().equals("coord_mismatch")));
    }

    @Test void matchingCoordinateIsNotFlagged() {
        var warn = RddPlanLint.check("把东西放进 360,66,636 的箱子", 360, 66, 636, "container_at", "minecraft:shield");
        assertTrue(warn.stream().noneMatch(w -> w.rule().equals("coord_mismatch")));
    }

    @Test void containerTakeOutIsFlagged() {
        var warn = RddPlanLint.check("从箱子取出铁胸甲", 360, 66, 636, "container_at", "minecraft:iron_chestplate");
        assertTrue(warn.stream().anyMatch(w -> w.rule().equals("container_direction")));
    }

    @Test void assumeHaveIsFlaggedOnlyWithItemCondition() {
        var warn = RddPlanLint.check("包里已有铁胸甲，不用再做", 360, 66, 636, "container_at", "minecraft:iron_chestplate");
        assertTrue(warn.stream().anyMatch(w -> w.rule().equals("assume_have")));
        var noItem = RddPlanLint.check("包里已有铁胸甲", 360, 66, 636, "container_at", "");
        assertTrue(noItem.stream().noneMatch(w -> w.rule().equals("assume_have")));
    }

    @Test void dedupesPerSubtaskAndRule() {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        assertTrue(RddPlanLint.firstWarning(seen, "p1", "s1", "coord_mismatch"));
        assertFalse(RddPlanLint.firstWarning(seen, "p1", "s1", "coord_mismatch"));
        assertTrue(RddPlanLint.firstWarning(seen, "p1", "s1", "container_direction"));
    }
}
