package com.dwinovo.numen.api.observation;

import com.dwinovo.numen.api.observation.WorldObservationService.Coverage;
import com.dwinovo.numen.api.observation.WorldObservationService.Item;
import com.dwinovo.numen.api.observation.WorldObservationService.Request;
import com.dwinovo.numen.api.observation.WorldObservationService.Response;
import com.dwinovo.numen.api.observation.WorldObservationService.Status;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 039 U1：观测读口的契约。重点不是"能读"，而是四种状态与"缺字段"分得开。
 */
class WorldObservationServiceContractTest {

    @Test
    void requestRequiresATopicAndNormalises() {
        assertThrows(IllegalArgumentException.class,
                () -> new Request("rdd-env", "minecraft:overworld", " ", Map.of(), 0, 0, 0));
        Request r = new Request(null, " minecraft:overworld ", " environment ", null, 500, 20, 50);
        assertEquals("unknown", r.consumer(), "consumer 只是审计标签，缺省要显式");
        assertEquals("minecraft:overworld", r.dimension());
        assertEquals("environment", r.topic());
        assertTrue(r.selector().isEmpty());
    }

    @Test
    void errorResponseMustCarryAReason() {
        assertThrows(IllegalArgumentException.class, () -> new Response(Status.ERROR, "env", "", 0, Coverage.UNKNOWN, null, List.of(), 0L));
        Response e = Response.error("env", "provider missing");
        assertEquals(Status.ERROR, e.status());
        assertFalse(e.hasItems());
    }

    @Test
    void okIsNotACompletedConditionAndEmptyIsNotAbsence() {
        Response emptyPartial = new Response(Status.OK, "combat.crystals", "snap-1", 100, Coverage.PARTIAL, null, List.of(), 0L);
        assertFalse(emptyPartial.hasItems(), "没有条目");
        assertEquals(Coverage.PARTIAL, emptyPartial.coverage(),
                "局部覆盖不能据此证明不存在（调用方要自己判 canProveAbsence）");
    }

    @Test
    void missingFieldsAreMarkedNotZeroFilled() {
        Item i = new Item(Map.of("health", 120), Set.of("phase"), Set.of("crystalsLeft"));
        assertEquals(120, i.values().get("health"));
        assertTrue(i.unknownFields().contains("phase"), "没观测到的字段必须点名");
        assertTrue(i.staleFields().contains("crystalsLeft"));
        assertFalse(i.values().containsKey("phase"), "不许用 0/false 冒充未知");
    }

    @Test
    void collectionsAreImmutable() {
        Response r = Response.ok("env", "s", 1, Coverage.FULL, List.of(Item.of(Map.of("k", "v"))));
        assertThrows(UnsupportedOperationException.class, () -> r.items().add(Item.of(Map.of())));
        assertThrows(UnsupportedOperationException.class,
                () -> r.items().get(0).values().put("x", "y"));
    }

    @Test
    void pendingIsNeitherOkNorError() {
        Response p = new Response(Status.PENDING, "entities", "", 0, Coverage.UNKNOWN, "refresh started", List.of(), 0L);
        assertEquals(Status.PENDING, p.status());
        assertFalse(p.hasItems());
    }
    @Test
    void snapshotCarriesWatermarkForTheSeam() {
        // R3-3：响应必须带水位，否则"先 read 拿水位 → 再 changes"走不通
        Response r = new Response(Status.OK, "containers", "snap-2", 100, Coverage.FULL,
                null, List.of(), 4321L);
        assertEquals(4321L, r.watermark());
        assertEquals(0L, Response.error("x", "y").watermark(), "错误响应没有水位");
    }
}
