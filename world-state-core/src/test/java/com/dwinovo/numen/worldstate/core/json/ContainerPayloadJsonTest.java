package com.dwinovo.numen.worldstate.core.json;

import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Origin;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.observation.payload.ContainerPayload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 039 U3：容器的"已知为空"与"容器里有东西"要能往返，且请求目标格式有硬约束。
 */
class ContainerPayloadJsonTest {

    @Test
    void emptyContainerIsAKnownEmptyMapNotUnknown() {
        ContainerPayload p = new ContainerPayload("minecraft:overworld", "block:361,59,677", 27, Map.of());
        assertEquals(0, p.totalItems());
        assertTrue(p.items().isEmpty(), "已知为空是空表，不是 null/未知");
        assertEquals(Topic.CONTAINERS, p.topic());
    }

    @Test
    void roundTripKeepsItemsAndTarget() {
        ContainerPayload p = new ContainerPayload("minecraft:overworld", "block:361,59,677", 27,
                Map.of("minecraft:iron_ingot", 32, "minecraft:torch", 16));
        ObservationKey key = ObservationKey.of("live", "minecraft:overworld", Topic.CONTAINERS,
                "block:361,59,677", "");
        Observation o = new Observation(key, p, "containers", Origin.DIRECT, 10L, 1_700_000_000_000L,
                Coverage.COMPLETE, null, 3L, 1);
        String json = ObservationJson.toJson(List.of(o));
        assertTrue(ObservationJson.canDecode(Topic.CONTAINERS));
        List<Observation> back = ObservationJson.fromJson(json);
        assertEquals(1, back.size());
        ContainerPayload q = (ContainerPayload) back.get(0).payload();
        assertEquals(32, q.items().get("minecraft:iron_ingot"));
        assertEquals(16, q.items().get("minecraft:torch"));
        assertEquals(27, q.slots());
        assertEquals("block:361,59,677", q.target());
    }

    @Test
    void requestTargetMustBeTyped() {
        assertThrows(IllegalArgumentException.class,
                () -> com.dwinovo.numen.worldstate.core.request.ObservationRequest
                        .containers("minecraft:overworld", "just-a-string"));
        assertThrows(IllegalArgumentException.class,
                () -> com.dwinovo.numen.worldstate.core.request.ObservationRequest
                        .containers("minecraft:overworld", ""));
        var req = com.dwinovo.numen.worldstate.core.request.ObservationRequest
                .containers("minecraft:overworld", "block:1,2,3");
        assertEquals(Topic.CONTAINERS, req.topic());
        assertEquals("block:1,2,3", req.target());
    }
}
