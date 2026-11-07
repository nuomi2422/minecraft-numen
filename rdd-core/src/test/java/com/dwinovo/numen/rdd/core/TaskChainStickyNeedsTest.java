package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskChainStickyNeedsTest {
    private static TaskChain chain() {
        return new TaskChain(new Goal("g","sticky", java.util.List.of(
                new PrimaryGoal("p","p", java.util.List.of(), java.util.List.of(), true))));
    }
    @Test void stickySurvivesSnapshotRoundTrip() {
        TaskChain c = chain();
        c.recordSatisfiedNeed("p|r0|a0|minecraft:wheat");
        assertTrue(c.snapshot().toString().contains("satisfiedNeedKeys"));
        TaskChain back = TaskChain.fromJson(c.toJson());
        assertTrue(back.isSatisfiedNeed("p|r0|a0|minecraft:wheat"));
    }
    @Test void oldSnapshotWithoutFieldDefaultsEmpty() {
        TaskChain c = chain();
        var obj = com.google.gson.JsonParser.parseString(c.toJson()).getAsJsonObject();
        obj.remove("satisfiedNeedKeys");
        TaskChain back = TaskChain.fromJson(obj.toString());
        assertTrue(back.satisfiedNeedKeys().isEmpty(), "旧快照缺字段必须默认空");
    }
    @Test void newPlanRevisionClearsSticky() {
        TaskChain c = chain();
        c.recordSatisfiedNeed("p|r0|a0|minecraft:wheat");
        c.nextPlanRevision();
        assertTrue(c.satisfiedNeedKeys().isEmpty());
    }
}
