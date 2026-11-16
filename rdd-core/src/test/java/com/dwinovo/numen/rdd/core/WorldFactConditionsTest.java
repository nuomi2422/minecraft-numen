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
        // 2026-11-06：any 覆盖主副手（记录 006 的 2/2 实测）；mainhand/offhand 为新增精确槽位。
        var held = Map.<String, Object>of("type", "equipment", "item", "minecraft:diamond_sword", "slot", "any");
        assertTrue(WorldFactConditions.valid(held));
        assertTrue(WorldFactConditions.equipmentSlotsSatisfied(held, false, false, false, false, true, false));
        assertFalse(WorldFactConditions.equipmentSlotsSatisfied(held, false, false, false, false, false, false));
        var mainOnly = Map.<String, Object>of("type", "equipment", "item", "minecraft:diamond_pickaxe", "slot", "mainhand");
        assertTrue(WorldFactConditions.valid(mainOnly));
        assertTrue(WorldFactConditions.equipmentSlotsSatisfied(mainOnly, false, false, false, false, true, false));
        assertFalse(WorldFactConditions.equipmentSlotsSatisfied(mainOnly, false, false, false, false, false, true));
        var offOnly = Map.<String, Object>of("type", "equipment", "item", "minecraft:shield", "slot", "offhand");
        assertTrue(WorldFactConditions.valid(offOnly));
        assertTrue(WorldFactConditions.equipmentSlotsSatisfied(offOnly, false, false, false, false, false, true));
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

    @Test void equipmentSlotsSchemaAcceptsCompleteCanonicalArmorRequirements() {
        var slots = Map.of("feet", "minecraft:iron_boots", "legs", "minecraft:iron_leggings",
                "chest", "minecraft:iron_chestplate", "head", "minecraft:iron_helmet");
        var fullArmor = Map.<String, Object>of("type", "equipment", "slots", slots);
        var taggedSlots = Map.of("feet", "#minecraft:foot_armor", "legs", "#minecraft:leg_armor",
                "chest", "#minecraft:chest_armor", "head", "#minecraft:head_armor");
        assertAll(
                () -> assertTrue(WorldFactConditions.valid(fullArmor), "A complete canonical armor map is a valid equipment fact"),
                () -> assertTrue(WorldFactConditions.valid(Map.of("type", "equipment", "slots", taggedSlots))),
                () -> assertTrue(WorldFactConditions.valid(Map.of("type", "equipment", "slots", slots,
                        "dimension", "minecraft:overworld", "optional", true))),
                () -> assertTrue(WorldFactConditions.valid(Map.of("type", "equipment", "slots", slots, "optional", false))));
    }

    @Test void equipmentSlotsRequireEveryArmorHitFromOneSampleNotHandsOrInventory() {
        var fullArmor = Map.<String, Object>of("type", "equipment", "slots", Map.of(
                "feet", "minecraft:iron_boots", "legs", "minecraft:iron_leggings",
                "chest", "minecraft:iron_chestplate", "head", "minecraft:iron_helmet"));
        // Each hit matches that slot against its own wanted item in the same server sample, not independent scans.
        // This pure boolean core proves current full wear only, never that a wear action completed in this step.
        assertAll(
                () -> assertTrue(WorldFactConditions.equipmentSlotsSatisfied(fullArmor, true, true, true, true)),
                () -> assertTrue(WorldFactConditions.equipmentSlotsSatisfied(fullArmor, true, true, true, true, false, false)),
                () -> assertTrue(WorldFactConditions.equipmentSlotsSatisfied(fullArmor, true, true, true, true, true, true)),
                () -> assertFalse(WorldFactConditions.equipmentSlotsSatisfied(fullArmor, false, true, true, true)),
                () -> assertFalse(WorldFactConditions.equipmentSlotsSatisfied(fullArmor, true, false, true, true)),
                () -> assertFalse(WorldFactConditions.equipmentSlotsSatisfied(fullArmor, true, true, false, true)),
                () -> assertFalse(WorldFactConditions.equipmentSlotsSatisfied(fullArmor, true, true, true, false)),
                () -> assertFalse(WorldFactConditions.equipmentSlotsSatisfied(fullArmor, false, true, true, true, true, true)),
                () -> assertFalse(WorldFactConditions.equipmentSlotsSatisfied(fullArmor, true, false, true, true, true, true)),
                () -> assertFalse(WorldFactConditions.equipmentSlotsSatisfied(fullArmor, true, true, false, true, true, true)),
                () -> assertFalse(WorldFactConditions.equipmentSlotsSatisfied(fullArmor, true, true, true, false, true, true)),
                () -> assertFalse(WorldFactConditions.equipmentSlotsSatisfied(fullArmor, false, false, false, false, true, true)),
                () -> assertFalse(WorldFactConditions.equipmentSlotsSatisfied(fullArmor, false, false, false, false)),
                () -> assertFalse(HardCodedEvaluator.matches(fullArmor, Map.of(
                        "minecraft:iron_boots", 1, "minecraft:iron_leggings", 1,
                        "minecraft:iron_chestplate", 1, "minecraft:iron_helmet", 1)), "A full set only in inventory is not worn armor"));
    }

    @Test void equipmentSlotsRejectPartialMixedAndMalformedRequirements() {
        var slots = Map.of("feet", "minecraft:iron_boots", "legs", "minecraft:iron_leggings",
                "chest", "minecraft:iron_chestplate", "head", "minecraft:iron_helmet");
        for (String slot : slots.keySet()) {
            var partial = new java.util.HashMap<String, Object>(slots);
            partial.remove(slot);
            assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "slots", partial)), "Missing " + slot);
        }
        for (String unknownSlot : java.util.List.of("boots", "leggings", "chestplate", "helmet", "mainhand", "offhand", "any", "HEAD")) {
            var mixed = new java.util.HashMap<String, Object>(slots);
            mixed.remove("head");
            mixed.put(unknownSlot, "minecraft:iron_helmet");
            assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "slots", mixed)), "Noncanonical slot " + unknownSlot);
            var extra = new java.util.HashMap<String, Object>(slots);
            extra.put(unknownSlot, "minecraft:iron_helmet");
            assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "slots", extra)), "Extra slot " + unknownSlot);
        }
        for (String field : java.util.List.of("item", "slot", "minimum", "mode", "allof", "types")) {
            var mixed = new java.util.HashMap<String, Object>(Map.of("type", "equipment", "slots", slots));
            mixed.put(field, switch (field) {
                case "minimum" -> 1;
                case "item" -> "minecraft:iron_helmet";
                case "slot" -> "any";
                case "mode" -> "acquire";
                default -> java.util.List.of(Map.of("type", "base"));
            });
            assertFalse(WorldFactConditions.valid(mixed), "slots must not mix with " + field);
        }
        for (String badId : java.util.List.of("iron_helmet", "not valid", "#", "#iron_helmet", "minecraft:", "minecraft:iron helmet")) {
            var malformed = new java.util.HashMap<String, Object>(slots);
            malformed.put("head", badId);
            assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "slots", malformed)), "Bad item or tag " + badId);
        }
        var nullValue = new java.util.HashMap<String, Object>(slots);
        nullValue.put("head", null);
        assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "slots", nullValue)));
        var mixedValueTypes = new java.util.HashMap<String, Object>(slots);
        mixedValueTypes.put("head", 1);
        assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "slots", mixedValueTypes)));
        assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "slots", Map.of())));
        assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "slots", "minecraft:iron_helmet")));
        var nullSlots = new java.util.HashMap<String, Object>(Map.of("type", "equipment"));
        nullSlots.put("slots", null);
        assertFalse(WorldFactConditions.valid(nullSlots));
        assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "slots", slots, "dimension", "not valid")));
        assertFalse(WorldFactConditions.valid(Map.of("type", "equipment", "slots", slots, "optional", "true")));
    }
}