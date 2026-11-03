package com.dwinovo.numen.rdd.core;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** 169 实机：统计类条件默认 delta，显式 scope=lifetime 才走终身累计。 */
class WorldFactConditionsScopeTest {

    private static Map<String, Object> bread(String scope) {
        Map<String, Object> c = new java.util.LinkedHashMap<>();
        c.put("type", "item_crafted");
        c.put("item", "minecraft:bread");
        c.put("minimum", 1);
        if (scope != null) c.put("scope", scope);
        return c;
    }

    @Test
    void defaultAndExplicitScopesAreAccepted() {
        assertTrue(WorldFactConditions.valid(bread(null)));
        assertTrue(WorldFactConditions.valid(bread("delta")));
        assertTrue(WorldFactConditions.valid(bread("lifetime")));
        assertFalse(WorldFactConditions.valid(bread("forever")));
    }

    @Test
    void bothFoodTagSpellingsAreRecognized() {
        assertTrue(WorldFactConditions.isFoodTag("minecraft:food"));
        assertTrue(WorldFactConditions.isFoodTag("minecraft:is_food"));
        assertFalse(WorldFactConditions.isFoodTag("minecraft:stone"));
    }

    @Test
    void foodTagFallbackIsLimitedToContainerConditions() {
        assertTrue(WorldFactConditions.supportsFoodTagFallback("container_at"));
        assertTrue(WorldFactConditions.supportsFoodTagFallback("container_nearby"));
        assertFalse(WorldFactConditions.supportsFoodTagFallback("item_crafted"));
        assertFalse(WorldFactConditions.supportsFoodTagFallback("item_used"));
        assertFalse(WorldFactConditions.supportsFoodTagFallback("block_mined"));
        assertFalse(WorldFactConditions.supportsFoodTagFallback(null));
    }

    @Test
    void lifetimeScopeIsDetectedOnlyWhenExplicit() {
        assertFalse(WorldFactConditions.lifetimeScope(bread(null)));
        assertTrue(WorldFactConditions.lifetimeScope(bread("lifetime")));
    }
}
