package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class RddResourceUseProjectionTest {
    private static final String STONE = "minecraft:cobblestone";
    private static Subtask step(String id, Map<String, Object> condition, ResourceUse... uses) {
        return new Subtask(id, id, DetectionMode.HARD_CODED, condition, 0, 0, false,
                null, NodeType.MAIN, null, List.of(uses));
    }
    private static Subtask gather() { return step("gather", Map.of("asset_key", STONE, "minimum", 8)); }
    private static Subtask furnace(ResourceUse... more) {
        var uses = new java.util.ArrayList<ResourceUse>();
        uses.add(new ResourceUse(STONE, 8, ResourceUse.Kind.CONSUME));
        uses.addAll(List.of(more));
        return step("furnace", Map.of("type", "block_nearby", "block", "minecraft:furnace", "radius", 8),
                uses.toArray(ResourceUse[]::new));
    }
    private static PrimaryGoal primary(Subtask... steps) {
        return new PrimaryGoal("p", "有界资源用途测试", List.of(steps), List.of());
    }
    private static int quantity(PrimaryGoal p, Map<String, SubtaskStatus> state, String key) {
        return RddFactContext.needsOf(p, state).stream().filter(n -> n.key().equals(key))
                .mapToInt(RddFactContext.Need::minimum).max().orElse(0);
    }

    @Test void verifiedConsumerEndsItsMaterialPurposeWithoutClaimingAChange() {
        var p = primary(gather(), furnace());
        var before = Map.of("gather", SubtaskStatus.COMPLETED, "furnace", SubtaskStatus.RUNNING);
        assertEquals(8, quantity(p, before, STONE));
        assertTrue(RddResourceUseProjection.pendingMaterialOnly(p, before, STONE, 0),
                "a pending declared input must not be called confirmed loss");
        var after = Map.of("gather", SubtaskStatus.COMPLETED, "furnace", SubtaskStatus.COMPLETED);
        assertEquals(0, quantity(p, after, STONE));
        String description = RddResourceUseProjection.describe(p, after);
        assertTrue(description.contains("PURPOSE_ENDED_NOT_CHANGE_PROOF"), description);
        assertFalse(description.contains("CONSUMPTION_CONFIRMED"));
        assertTrue(RddRequirementDetector.forPrimary(p, "goal", after).requirements().isEmpty());
    }

    @Test void retainedStockAndOtherConsumersCannotBeCancelledByTheFurnace() {
        var retain = new ResourceUse(STONE, 7, ResourceUse.Kind.RETAIN);
        var next = step("next", Map.of("asset_key", "minecraft:stone_pickaxe", "minimum", 1),
                new ResourceUse(STONE, 3, ResourceUse.Kind.CONSUME));
        var p = primary(gather(), furnace(retain), next);
        var state = Map.of("gather", SubtaskStatus.COMPLETED, "furnace", SubtaskStatus.COMPLETED,
                "next", SubtaskStatus.PENDING);
        assertEquals(10, quantity(p, state, STONE));
        assertFalse(RddResourceUseProjection.pendingMaterialOnly(p, state, STONE, 0), "retained7 truly missing");
        assertTrue(RddResourceUseProjection.pendingMaterialOnly(p, state, STONE, 7), "7 retained but3 pendinginput unknown");
        assertFalse(ResourceUse.retainedSatisfied(List.of(retain), Map.of(STONE, 0)));
    }

    @Test void skippedUnknownAndLegacyStepsDoNotBecomeVerifiedConsumption() {
        var p = primary(gather(), furnace());
        assertEquals(8, quantity(p, Map.of(), STONE), "declarations alone do not end the input purpose");
        assertEquals(8, quantity(p, Map.of("gather", SubtaskStatus.COMPLETED,
                "furnace", SubtaskStatus.SKIPPED), STONE));
        var legacy = primary(gather());
        assertEquals(8, quantity(legacy, Map.of("gather", SubtaskStatus.COMPLETED), STONE));
        assertEquals("", RddResourceUseProjection.describe(legacy, Map.of()));
    }

    @Test void exactTransferRequiresTheRealConsumerResultNotAnInventoryDrop() {
        String food = "minecraft:cooked_beef";
        var acquire = step("food", Map.of("asset_key", food, "minimum", 8));
        var put = step("put", Map.of("type", "container_at", "dimension", "minecraft:overworld",
                "x", 1, "y", 64, "z", 2, "item", food, "minimum", 8),
                new ResourceUse(food, 8, ResourceUse.Kind.TRANSFER));
        var p = primary(acquire, put);
        assertEquals(8, quantity(p, Map.of("food", SubtaskStatus.COMPLETED, "put", SubtaskStatus.RUNNING), food));
        assertEquals(0, quantity(p, Map.of("food", SubtaskStatus.COMPLETED, "put", SubtaskStatus.COMPLETED), food));
        assertThrows(IllegalArgumentException.class, () -> step("bad", Map.of("type", "stocked", "minimum", 10),
                new ResourceUse(food, 8, ResourceUse.Kind.TRANSFER)));
    }

    @Test void repeatedProducerOrUnexplainedSurplusDoesNotInventASourceLink() {
        var other = step("gather2", Map.of("asset_key", STONE, "minimum", 8));
        var p = primary(gather(), other, furnace());
        assertEquals(8, quantity(p, Map.of("gather", SubtaskStatus.COMPLETED,
                "gather2", SubtaskStatus.COMPLETED, "furnace", SubtaskStatus.COMPLETED), STONE));
        var surplus = step("surplus", Map.of("asset_key", STONE, "minimum", 70));
        assertEquals(70, quantity(primary(surplus, furnace()), Map.of("surplus", SubtaskStatus.COMPLETED,
                "furnace", SubtaskStatus.COMPLETED), STONE));
    }

    @Test void distinctPendingConsumersAndRetainedQuantitiesShareOneTruth() {
        var second = step("second", Map.of("asset_key", "minecraft:stone_pickaxe", "minimum", 1),
                new ResourceUse(STONE, 3, ResourceUse.Kind.CONSUME));
        var p = primary(gather(), furnace(new ResourceUse(STONE, 2, ResourceUse.Kind.RETAIN)), second);
        var state = Map.of("gather", SubtaskStatus.COMPLETED, "furnace", SubtaskStatus.RUNNING,
                "second", SubtaskStatus.PENDING);
        assertEquals(13, quantity(p, state, STONE));
        var manifest = RddRequirementDetector.forPrimary(p, "goal", state);
        assertEquals(13, manifest.requirements().stream().filter(r -> r.key().equals(STONE)).findFirst().orElseThrow().minimum());
        assertFalse(RddFactContext.unmetKeys(RddFactContext.needsOf(p, state), Map.of(STONE, 13)).contains(STONE));
    }
}
