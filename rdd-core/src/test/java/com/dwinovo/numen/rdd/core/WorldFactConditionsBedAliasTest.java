package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 169 实机：床的颜色变体不能把 block_nearby 判据变成永远假。 */
class WorldFactConditionsBedAliasTest {

    @Test
    void anyColoredBedIsTreatedAsBed() {
        assertTrue(WorldFactConditions.isBedBlockId("minecraft:white_bed"));
        assertTrue(WorldFactConditions.isBedBlockId("minecraft:purple_bed"));
        assertTrue(WorldFactConditions.isBedBlockId("minecraft:bed"));
    }

    @Test
    void nonBedBlocksAreNotAliased() {
        assertFalse(WorldFactConditions.isBedBlockId("minecraft:chest"));
        assertFalse(WorldFactConditions.isBedBlockId("minecraft:bedrock"));
        assertFalse(WorldFactConditions.isBedBlockId(null));
    }
}
