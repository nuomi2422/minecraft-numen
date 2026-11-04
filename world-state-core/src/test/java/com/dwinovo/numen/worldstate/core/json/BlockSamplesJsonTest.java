package com.dwinovo.numen.worldstate.core.json;

import com.dwinovo.numen.worldstate.core.model.Bounds;
import com.dwinovo.numen.worldstate.core.model.Coverage;
import com.dwinovo.numen.worldstate.core.model.Origin;
import com.dwinovo.numen.worldstate.core.model.Topic;
import com.dwinovo.numen.worldstate.core.observation.Observation;
import com.dwinovo.numen.worldstate.core.observation.ObservationKey;
import com.dwinovo.numen.worldstate.core.observation.payload.BlockCountPayload;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 039 U5#8 收尾：位置样本随方块计数一起存/取。 */
class BlockSamplesJsonTest {

    @Test
    void samplesSurviveRoundTrip() {
        BlockCountPayload p = new BlockCountPayload("minecraft:overworld",
                new Bounds(0, 0, 0, 8, 8, 8), List.of("minecraft:wheat[age=7]"),
                Map.of("minecraft:wheat[age=7]", 60), 81, 0,
                List.of("minecraft:wheat[age=7]@1,64,2", "minecraft:wheat[age=7]@3,64,4"));
        ObservationKey key = ObservationKey.of("live", "minecraft:overworld", Topic.BLOCKS,
                "box=0,0,0..8,8,8 samples=2", "block=minecraft:wheat[age=7]");
        Observation o = new Observation(key, p, "blocks", Origin.DIRECT, 1L, 1_700_000_000_000L,
                Coverage.COMPLETE, null, 1L, 1);
        List<Observation> back = ObservationJson.fromJson(ObservationJson.toJson(List.of(o)));
        BlockCountPayload q = (BlockCountPayload) back.get(0).payload();
        assertEquals(60, q.counts().get("minecraft:wheat[age=7]"));
        assertEquals(2, q.samplePositions().size());
        assertTrue(q.samplePositions().get(0).endsWith("@1,64,2"));
    }

    @Test
    void legacyConstructorHasNoSamples() {
        BlockCountPayload p = new BlockCountPayload("minecraft:overworld",
                new Bounds(0, 0, 0, 1, 1, 1), List.of("minecraft:dirt"), Map.of("minecraft:dirt", 3), 1, 0);
        assertTrue(p.samplePositions().isEmpty(), "不带样本时必须是空表，不能凭空造位置");
    }
}
