package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.EarlyCompletion;
import com.dwinovo.numen.rdd.core.TaskChain;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 044 §0.7: an item seen in a chest is not "obtained". Early completion may only read the
 * companion's own inventory, so a container-sourced condition must never mark a stage done.
 */
class RddEarlyCompletionEvidenceTest {

    private static TaskChain chain(Subtask condition) {
        return new TaskChain(new Goal("g", "g", List.of(
                new PrimaryGoal("p1", "stage", List.of(condition)))));
    }

    private static Subtask item(String id, String source) {
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("type", "item");
        c.put("item", "minecraft:diamond");
        c.put("minimum", 1);
        if (source != null) c.put("source", source);
        return Subtask.hardCoded(id, id, c);
    }

    @Test void containerSourceIsNeverEvidenceOfOwnership() {
        TaskChain c = chain(item("chest", "container"));
        Map<String, Integer> counts = Map.of("minecraft:diamond", 64);
        assertTrue(EarlyCompletion.satisfied(c, counts).isEmpty(),
                "items sitting in a chest must not satisfy an early completion");
    }

    @Test void ownInventoryStillSatisfies() {
        TaskChain c = chain(item("own", "inventory"));
        assertTrue(EarlyCompletion.satisfied(c, Map.of("minecraft:diamond", 0)).isEmpty());
        assertEquals(1, EarlyCompletion.satisfied(c, Map.of("minecraft:diamond", 1)).size());
    }

    @Test void unknownSourceIsSkippedNotAssumed() {
        TaskChain c = chain(item("unknown", "some_other_vendor"));
        assertTrue(EarlyCompletion.satisfied(c, Map.of("minecraft:diamond", 64)).isEmpty(),
                "an unmodelled source must be skipped, not treated as inventory");
    }

    @Test void wrongPlanRevisionYieldsNoCompletion() {
        TaskChain c = chain(item("own", "inventory"));
        Map<String, Integer> counts = Map.of("minecraft:diamond", 1);
        assertEquals(1, EarlyCompletion.satisfied(c, counts, c.planRevision()).size());
        assertTrue(EarlyCompletion.satisfied(c, counts, c.planRevision() + 1).isEmpty(),
                "a stale generation must not complete anything");
    }
}
