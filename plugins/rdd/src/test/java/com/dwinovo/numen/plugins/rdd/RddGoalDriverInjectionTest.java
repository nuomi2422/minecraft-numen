package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 2026-11-08: runtime injection seam (not factory-only) must write DAILY+recurrence into the chain. */
class RddGoalDriverInjectionTest {
    @Test void injectedDailyReachesChainWithRecurrence() {
        TaskChain chain = new TaskChain(new Goal("g", "维护农田",
                List.of(new PrimaryGoal("p", "维护农田", List.of(), List.of(), true))));
        SubtaskSpec daily = new SubtaskSpec("每天收麦补种",
                Map.of("asset_key", "minecraft:wheat", "minimum", 1), null,
                NodeType.DAILY, new RecurrenceSpec(1, RecurrenceSpec.MissedPolicy.SKIP, true));
        SubtaskSpec main = new SubtaskSpec("开垦农田",
                Map.of("asset_key", "minecraft:iron_hoe", "minimum", 1), null, NodeType.MAIN, null);
        var subs = RddGoalDriver.buildInjection("p", 1, List.of(daily, main));
        chain.expandCurrentPrimary(subs);
        var written = chain.currentPrimary().subtasks();
        assertEquals(2, written.size());
        assertEquals(NodeType.DAILY, written.get(0).nodeType(), "运行期注入后链里必须仍是 DAILY");
        assertNotNull(written.get(0).recurrence());
        assertEquals(NodeType.MAIN, written.get(1).nodeType());
    }
}
