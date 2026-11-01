package com.dwinovo.numen.rdd.policy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-01 实机：它长期背着一包东西，而基地箱子是空的。
 * 「箱子里缺 8 个火把」和「背包有 30 个火把」同时成立时，正确的下一步是放进箱子，而不是去做火把。
 */
class ReserveActionTest {

    @Test
    void itemsAlreadyInInventoryBecomePutNotGather() {
        var steps = ReserveAction.plan(List.of("torch 0/8", "cooked_beef 0/8"),
                Map.of("minecraft:torch", 30, "minecraft:cooked_beef", 2));
        assertEquals(2, steps.size());
        var torch = steps.stream().filter(s -> s.itemId().endsWith("torch")).findFirst().orElseThrow();
        assertTrue(torch.fromInventory(), "背包里有 30 个火把 -> 应该是放进去");
        assertEquals(8, torch.need());
        var beef = steps.stream().filter(s -> s.itemId().endsWith("cooked_beef")).findFirst().orElseThrow();
        assertFalse(beef.fromInventory(), "只有 2 个熟牛肉，不够 8 个 -> 应该是去取");
        assertTrue(ReserveAction.describe(steps).contains("put torch 8"));
        assertTrue(ReserveAction.describe(steps).contains("gather cooked_beef 8"));
    }

    @Test
    void partialShortfallAsksOnlyForTheGap() {
        var steps = ReserveAction.plan(List.of("cooked_beef 3/8"), Map.of("minecraft:cooked_beef", 10));
        assertEquals(5, steps.get(0).need(), "已有 3，只需补 5");
        assertTrue(steps.get(0).fromInventory());
    }

    @Test
    void emptyOrMalformedInputIsIgnored() {
        assertTrue(ReserveAction.plan(List.of(), Map.of()).isEmpty());
        assertTrue(ReserveAction.plan(null, null).isEmpty());
        assertTrue(ReserveAction.plan(List.of("garbage"), Map.of()).isEmpty());
        assertEquals("", ReserveAction.describe(List.of()));
    }
}