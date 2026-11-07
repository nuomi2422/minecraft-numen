package com.dwinovo.numen.rdd.api;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NodeTypeModelTest {
    @Test void legacySubtaskSpecDefaultsToMain() {
        SubtaskSpec old = new SubtaskSpec("收麦", Map.of("asset_key","minecraft:wheat","minimum",9), null);
        assertEquals(NodeType.MAIN, old.nodeType());
        assertNull(old.recurrence());
    }
    @Test void dailyRequiresFarmGateOnlyWhenConfigured() {
        RecurrenceSpec daily = RecurrenceSpec.daily(true);
        assertFalse(NodeTypePolicy.canActivate(NodeType.DAILY, daily, false));
        assertTrue(NodeTypePolicy.canActivate(NodeType.DAILY, daily, true));
        assertTrue(NodeTypePolicy.canActivate(NodeType.MAIN, daily, false));
        assertTrue(NodeTypePolicy.canActivate(NodeType.SIDE, daily, false));
    }
    @Test void dailyNeverCountsTowardMainline() {
        assertTrue(NodeTypePolicy.countsTowardMainline(NodeType.MAIN));
        assertTrue(NodeTypePolicy.countsTowardMainline(NodeType.SIDE));
        assertFalse(NodeTypePolicy.countsTowardMainline(NodeType.DAILY));
    }
}
