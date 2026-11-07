package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08: runtime stage-B injection path must keep DAILY+recurrence. */
class RddChainFactoryNodeTypeTest {
    @Test void injectionKeepsDailyAndRecurrence() {
        SubtaskSpec daily = new SubtaskSpec("日常收麦补种",
                Map.of("type", "block_nearby", "block", "minecraft:wheat", "radius", 8),
                null, NodeType.DAILY, new RecurrenceSpec(1, RecurrenceSpec.MissedPolicy.SKIP, true));
        SubtaskSpec main = new SubtaskSpec("开垦农田",
                Map.of("asset_key", "minecraft:iron_hoe", "minimum", 1), null, NodeType.MAIN, null);
        var subs = RddChainFactory.subtasksFromSpecs("primary-p", 4, List.of(daily, main));
        assertEquals(2, subs.size());
        assertEquals(NodeType.DAILY, subs.get(0).nodeType(), "运行期注入不得把 DAILY 归一成 MAIN");
        assertNotNull(subs.get(0).recurrence());
        assertTrue(subs.get(0).recurrence().requiresFarmBuilt());
        assertEquals(NodeType.MAIN, subs.get(1).nodeType());
        assertNull(subs.get(1).recurrence());
    }
}
