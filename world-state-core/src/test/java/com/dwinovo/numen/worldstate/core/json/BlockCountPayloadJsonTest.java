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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 039 U2b：blocks 载荷进存储/出存储能对上，"点名了但没看到"与"没点名"要分得开。
 */
class BlockCountPayloadJsonTest {

    private static Observation sample() {
        Bounds box = new Bounds(-8, -53, -8, 8, 71, 8);
        BlockCountPayload payload = new BlockCountPayload("minecraft:overworld", box,
                List.of("minecraft:chest", "minecraft:lava"),
                Map.of("minecraft:chest", 2), 17, 3);
        ObservationKey key = ObservationKey.of("world", "minecraft:overworld", Topic.BLOCKS,
                "box=" + box.canonical(), "block=minecraft:chest,minecraft:lava");
        return new Observation(key, payload, "blocks", Origin.DIRECT, 100L, 1_700_000_000_000L,
                Coverage.PARTIAL, null, 7L, 1);
    }

    @Test
    void declaredButUnseenBlocksAreZeroAndStillListed() {
        BlockCountPayload p = (BlockCountPayload) sample().payload();
        assertEquals(2, p.counts().get("minecraft:chest"));
        assertEquals(0, p.counts().get("minecraft:lava"), "点名了但没看到，要显式记 0");
        assertEquals(2, p.counts().size());
        assertEquals(2, p.total());
        assertEquals(3, p.skippedColumns(), "有列没加载，计数只是下限");
    }

    @Test
    void roundTripKeepsBlocksTopicAndCounts() {
        String json = ObservationJson.toJson(List.of(sample()));
        assertTrue(ObservationJson.canDecode(Topic.BLOCKS), "解码器要认得 blocks");
        List<Observation> back = ObservationJson.fromJson(json);
        assertEquals(1, back.size());
        Observation o = back.get(0);
        assertEquals(Topic.BLOCKS, o.key().topic());
        BlockCountPayload p = (BlockCountPayload) o.payload();
        assertEquals(2, p.counts().get("minecraft:chest"));
        assertEquals(0, p.counts().get("minecraft:lava"));
        assertEquals("block=minecraft:chest,minecraft:lava", p.filterId());
        // 载入的一律是 HISTORICAL：不能冒充本轮实时
        assertEquals(com.dwinovo.numen.worldstate.core.model.Freshness.HISTORICAL, o.freshness());
    }

    @Test
    void corruptedBlockPayloadIsDroppedNotThrown() {
        List<Observation> back = ObservationJson.fromJson(
                "{\"version\":1,\"observations\":[{\"worldId\":\"w\",\"dimension\":\"d\",\"topic\":\"blocks\","
                        + "\"payload\":\"not-an-object\",\"coverage\":\"COMPLETE\",\"revision\":1,\"dataVersion\":1}]}");
        assertTrue(back.isEmpty(), "坏载荷整条丢弃，不抛");
        assertFalse(ObservationJson.canDecode(Topic.CONTAINERS));
    }
}
