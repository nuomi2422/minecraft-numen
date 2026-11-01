package com.dwinovo.numen.rdd.policy;

import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 167 §B7：「农田存在」不等于「已有食物产出」。 */
class FarmProductionTest {

    private static FarmProduction.Sample s(long at, int wheat, int carrots) {
        return new FarmProduction.Sample(at, Map.of("minecraft:wheat", wheat, "minecraft:carrots", carrots));
    }

    @Test
    void noSamplesMeansUnknownNotZero() {
        assertEquals(FarmProduction.State.UNKNOWN, FarmProduction.evaluate(null, s(1, 5, 0)));
        assertEquals(FarmProduction.State.UNKNOWN, FarmProduction.evaluate(s(1, 5, 0), null));
        assertEquals("food=UNKNOWN", FarmProduction.describe(FarmProduction.State.UNKNOWN, null, 10));
    }

    @Test
    void anyNetGainCountsAsProducing() {
        assertEquals(FarmProduction.State.PRODUCING,
                FarmProduction.evaluate(s(0, 3, 0), s(10, 4, 0)), "小麦 +1 就算产出");
        assertEquals(FarmProduction.State.PRODUCING,
                FarmProduction.evaluate(s(0, 0, 0), s(10, 0, 2)), "胡萝卜 +2 也算");
    }

    @Test
    void flatOrConsumedIsNotProducing() {
        assertEquals(FarmProduction.State.NO_GAIN, FarmProduction.evaluate(s(0, 3, 1), s(10, 3, 1)));
        // 吃了也算"没有产出"：只有净增才算（吃了不该被当成田里长出来）
        assertEquals(FarmProduction.State.NO_GAIN, FarmProduction.evaluate(s(0, 5, 0), s(10, 2, 0)));
        assertTrue(FarmProduction.describe(FarmProduction.State.NO_GAIN, s(10, 0, 0), 70_000)
                .startsWith("food=NO_GAIN"));
    }

    @Test
    void cropCountsIgnoreNonCrops() {
        var counts = FarmProduction.cropCounts(Map.of(
                "minecraft:wheat", 2, "minecraft:melon_slice", 9, "minecraft:bread", 4));
        assertEquals(Map.of("minecraft:wheat", 2), counts);
    }
}
