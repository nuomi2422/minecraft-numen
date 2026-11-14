package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.DetectionMode;
import com.dwinovo.numen.rdd.api.Goal;
import com.dwinovo.numen.rdd.api.NodeType;
import com.dwinovo.numen.rdd.api.PrimaryGoal;
import com.dwinovo.numen.rdd.api.RecurrenceSpec;
import com.dwinovo.numen.rdd.api.Subtask;
import com.dwinovo.numen.rdd.core.TaskChain;
import com.dwinovo.numen.rdd.side.RecurringSideTaskSpec;
import com.dwinovo.numen.rdd.side.SideTaskEngine;
import com.dwinovo.numen.rdd.side.SideTaskEvents;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * 2026-11-15: RED seam lock for the existing producer flattening in
 * SideTaskHost.DailyRegistration. A DAILY subtask declared as
 * {type:block_nearby, block:#minecraft:crops, radius:16} must keep those typed
 * fields in the persisted recurring spec; only the old asset_key/item form is
 * allowed to be flattened. Current production keeps just asset_key, so this
 * test is expected to fail until the producer is fixed.
 */
class SideTaskDailyTypedConditionTest {
    private static final RecurrenceSpec SCHEDULE = RecurrenceSpec.daily(true);

    private static Subtask daily(String id, String description, Map<String, Object> condition) {
        return new Subtask(id, description, DetectionMode.HARD_CODED, condition, 0, 0, false, null,
                NodeType.DAILY, SCHEDULE);
    }

    private static TaskChain chain(Subtask... steps) {
        return new TaskChain(new Goal("g", "daily typed condition",
                List.of(new PrimaryGoal("p", "prepare", List.of(steps)))));
    }

    private static RecurringSideTaskSpec specFor(SideTaskEngine engine, String description) {
        return engine.recurringSpec("daily:g:" + Integer.toHexString(description.hashCode()));
    }

    @Test
    void blockNearbyDailyKeepsTypedConditionAndRadius() {
        String description = "block crops daily";
        Subtask sub = daily("d-block", description,
                Map.of("type", "block_nearby", "block", "#minecraft:crops", "radius", 16.0));
        SideTaskEngine engine = new SideTaskEngine(SideTaskEvents.NOOP, null);

        SideTaskHost.DailyRegistration.register(chain(sub), List.of(sub), engine);

        RecurringSideTaskSpec spec = specFor(engine, description);
        assertNotNull(spec, "typed DAILY must register a recurring spec");
        assertEquals("block_nearby", spec.data().get("type"),
                "producer must preserve condition.type instead of flattening it away");
        assertEquals("#minecraft:crops", spec.data().get("block"),
                "producer must preserve condition.block instead of flattening it to asset_key");
        String radius = spec.data().get("radius");
        assertNotNull(radius, "producer must preserve condition.radius");
        assertEquals(16.0, Double.parseDouble(radius), 1e-9, "radius must survive the producer");
    }

    @Test
    void legacyAssetKeyDailyKeepsItemAndMinimum() {
        String description = "legacy wheat daily";
        Subtask sub = daily("d-item", description,
                Map.of("asset_key", "minecraft:wheat", "minimum", 1));
        SideTaskEngine engine = new SideTaskEngine(SideTaskEvents.NOOP, null);

        SideTaskHost.DailyRegistration.register(chain(sub), List.of(sub), engine);

        RecurringSideTaskSpec spec = specFor(engine, description);
        assertNotNull(spec, "legacy DAILY must still register a recurring spec");
        assertEquals("minecraft:wheat", spec.data().get("asset_key"),
                "legacy asset_key item predicate must stay usable");
        assertEquals("1", spec.data().get("minimum"));
    }
}
