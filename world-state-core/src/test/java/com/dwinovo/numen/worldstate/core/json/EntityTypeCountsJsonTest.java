package com.dwinovo.numen.worldstate.core.json;

import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Origin;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.observation.payload.EntityCountPayload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 039 U5#7：按类别的实体计数要能带"按类型明细"，且旧的四参数构造还能用。
 */
class EntityTypeCountsJsonTest {

    @Test
    void legacyConstructorLeavesTypeCountsEmpty() {
        EntityCountPayload p = new EntityCountPayload("minecraft:overworld",
                new Bounds(0, 0, 0, 1, 1, 1), null, "all", 3, 4, 0);
        assertTrue(p.typeCounts().isEmpty(), "老调用不带明细，不能凭空编");
    }

    @Test
    void typeCountsSurviveRoundTrip() {
        EntityCountPayload p = new EntityCountPayload("minecraft:overworld",
                new Bounds(-8, -8, -8, 8, 8, 8), null, "hostile", 3, 289, 0,
                Map.of("minecraft:zombie", 2, "minecraft:skeleton", 1));
        ObservationKey key = ObservationKey.of("live", "minecraft:overworld", Topic.ENTITIES,
                "box=-8,-8,-8..8,8,8", "category=hostile");
        Observation o = new Observation(key, p, "entities", Origin.DIRECT, 5L, 1_700_000_000_000L,
                Coverage.COMPLETE, null, 1L, 1);
        List<Observation> back = ObservationJson.fromJson(ObservationJson.toJson(List.of(o)));
        assertEquals(1, back.size());
        EntityCountPayload q = (EntityCountPayload) back.get(0).payload();
        assertEquals("hostile", q.category());
        assertEquals(3, q.count());
        assertEquals(2, q.typeCounts().get("minecraft:zombie"));
        assertEquals(1, q.typeCounts().get("minecraft:skeleton"));
    }
}
