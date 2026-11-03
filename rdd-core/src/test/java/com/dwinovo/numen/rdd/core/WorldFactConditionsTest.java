package com.dwinovo.numen.rdd.core;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class WorldFactConditionsTest {
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

    @Test void equipmentConditionsFailClosedAndUseCurrentSlots() {
        var chest = Map.<String, Object>of("type", "equipment", "item", "minecraft:iron_chestplate", "slot", "chest");
        assertTrue(WorldFactConditions.valid(chest));
        assertTrue(WorldFactConditions.equipmentSlotsSatisfied(chest, false, false, true, false));
        assertFalse(WorldFactConditions.equipmentSlotsSatisfied(chest, true, false, false, false));
        assertFalse(WorldFactConditions.equipmentSlotsSatisfied(chest, false, false, false, false));
        var twoInChest = Map.<String, Object>of("type", "equipment", "item", "minecraft:iron_chestplate", "slot", "chest", "minimum", 2);
        assertFalse(WorldFactConditions.equipmentSlotsSatisfied(twoInChest, true, true, true, true));
        var any = Map.<String, Object>of("type", "equipment", "item", "#minecraft:trimmable_armor", "minimum", 3);
        assertTrue(WorldFactConditions.valid(any));
        assertTrue(WorldFactConditions.equipmentSlotsSatisfied(any, true, true, true, false));
        assertFalse(WorldFactConditions.equipmentSlotsSatisfied(any, true, false, true, false));
        assertTrue(WorldFactConditions.equipmentSlotsSatisfied(Map.of("type", "equipment", "item", "minecraft:iron_boots", "slot", "boots"), true, false, false, false));
        assertTrue(WorldFactConditions.equipmentSlotsSatisfied(Map.of("type", "equipment", "item", "minecraft:iron_helmet", "slot", "helmet"), false, false, false, true));
        assertTrue(WorldFactConditions.equipmentSlotsSatisfied(Map.of("type", "equipment", "item", "minecraft:iron_boots", "slot", "any"), true, false, false, false));
        assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "item", "minecraft:iron_chestplate", "slot", "hand")));
        assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "slot", "chest")));
        assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "item", "minecraft:iron_chestplate", "minimum", 0)));
        assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "item", "minecraft:iron_chestplate", "minimum", 1.5)));
        assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "item", "minecraft:iron_chestplate", "mode", "acquire")));
        assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "item", "minecraft:iron_chestplate", "dimension", "not valid")));
        var explicitNull = new java.util.HashMap<String, Object>(chest);
        explicitNull.put("slot", null);
        assertFalse(WorldFactConditions.valid(explicitNull));
        explicitNull.remove("slot");
        explicitNull.put("minimum", null);
        assertFalse(WorldFactConditions.valid(explicitNull));
    }
}