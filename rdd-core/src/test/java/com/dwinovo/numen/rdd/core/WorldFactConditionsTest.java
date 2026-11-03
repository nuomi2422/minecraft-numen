package com.dwinovo.numen.rdd.core;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class WorldFactConditionsTest {
    @Test void equipmentSchemaIsValid() {
        assertTrue(WorldFactConditions.valid(Map.of("type", "equipment", "item", "minecraft:iron_chestplate", "slot", "chest")));
        assertTrue(WorldFactConditions.valid(Map.of("type", "equipment", "item", "minecraft:iron_helmet", "slot", "head", "minimum", 1)));
        assertTrue(WorldFactConditions.valid(Map.of("type", "equipment", "item", "#minecraft:trimmable_armor")));
        assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "item", "minecraft:iron_chestplate", "slot", "hand")));
        assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "item", "not a real id")));
        assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "item", "minecraft:iron_chestplate", "minimum", 0)));
    }

    @Test void validatesExplicitEvidenceOnly() {
        assertTrue(WorldFactConditions.valid(Map.of("type", "base")));
        assertTrue(WorldFactConditions.valid(Map.of("type", "advancement", "advancement", "minecraft:story/mine_diamond")));
        assertTrue(WorldFactConditions.valid(Map.of("type", "structure", "structure", "minecraft:stronghold")));
        assertTrue(WorldFactConditions.valid(Map.of("type", "entity_killed", "entity", "minecraft:ender_dragon", "minimum", 1)));
        assertFalse(WorldFactConditions.valid(Map.of("type", "entity_killed", "entity", "minecraft:ender_dragon", "minimum", 0)));
        assertFalse(WorldFactConditions.valid(Map.of("type", "advancement", "advancement", "not real")));
        assertFalse(WorldFactConditions.valid(Map.of("type", "base", "asset_key", "minecraft:bed")));
        assertFalse(HardCodedEvaluator.matches(Map.of("type", "base", "asset_key", "minecraft:white_bed"), Map.of("minecraft:white_bed", 1)));
    }
}
