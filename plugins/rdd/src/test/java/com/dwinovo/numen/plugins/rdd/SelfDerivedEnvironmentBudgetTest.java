package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.observation.ObservationMetadata;
import com.dwinovo.numen.api.observation.WorldObservationService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Detached observations through the real environment builder; not a Minecraft runtime test. */
class SelfDerivedEnvironmentBudgetTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000056");
    private static final long NOW = 100_000L;
    private static final String DIMENSION = "example:" + "d".repeat(56);
    private static final String BED = "29999990,-64,29999990";

    @Test void foodAndBedCandidateLeaveNoRoomForAnOverBudgetKeyItemsFallback() {
        // At bbc7b831 this execution fixture renders 401 characters: the FOOD line
        // leaves one character for key items, but the omission fallback takes twelve.
        var slots = new ArrayList<Map<String, Object>>();
        String heldFood = "mod:" + "a".repeat(200);
        for (int i = 0; i < 36; i++) {
            slots.add(Map.of("slot", i, "id", i == 0 ? heldFood : "minecraft:bread",
                    "count", 64, "edible", true));
        }
        var self = response("self", ID.toString(), List.of(WorldObservationService.Item.of(Map.of(
                "companionUUID", ID.toString(), "dimension", DIMENSION,
                "main", slots, "mainHand", slots.get(0),
                "offhand", Map.of("slot", 40, "id", "mod:" + "b".repeat(200), "count", 64),
                "hp", 20, "food", 20,
                "rest", Map.of("x", 29999990, "y", -64, "z", 29999990,
                        "sleeping", false, "bedWorks", true)))));
        WorldObservationService service = new WorldObservationService() {
            @Override public Response readSelf(String consumer, UUID subject, long maxAgeMs) {
                assertEquals(ID, subject);
                assertEquals(3_000L, maxAgeMs);
                return self;
            }

            @Override public Response read(Request request) {
                assertEquals(DIMENSION, request.dimension());
                if ("environment".equals(request.topic())) {
                    return response("environment", "", List.of(Item.of(Map.of(
                            "day_time", 16000, "weather", "thunder"))));
                }
                assertEquals("blocks", request.topic());
                assertEquals("29999986,-66,29999986,29999994,-62,29999994", request.selector().get("box"));
                assertEquals(RddObservation.BEDS + "," + RddObservation.UNOCCUPIED_BED_HEADS,
                        request.selector().get("blocks"));
                assertEquals("1", request.selector().get("samples"));
                return response("blocks", "", List.of(
                        Item.of(Map.of("block", RddObservation.BEDS, "count", 2)),
                        Item.of(Map.of("block", RddObservation.UNOCCUPIED_BED_HEADS,
                                "count", 1, "samples", List.of(BED)))));
            }

            @Override public Response refresh(Request request) {
                throw new AssertionError("Environment rendering must only read detached observations");
            }
        };

        String output = RddEnvBlock.build(ID, service, () -> NOW, false);
        assertAll(
                () -> assertTrue(output.length() <= 400, "length=" + output.length() + "\n" + output),
                () -> assertTrue(output.contains("可食储备 2304件〔主包36槽/FOOD组件，非恢复值〕"), output),
                () -> assertTrue(output.contains("床头候选 (" + BED + ")（采样，可达/可睡未核）"), output),
                () -> assertTrue(output.startsWith("<env>\n") && output.endsWith("\n</env>"), output),
                () -> assertFalse(output.contains("背包 "), output));
    }

    private static WorldObservationService.Response response(String topic, String subject,
                                                              List<WorldObservationService.Item> items) {
        var metadata = new ObservationMetadata("budget-world", "budget-session", DIMENSION, subject,
                topic + "-fixture", topic + "-budget-key", 56, NOW - 100,
                ObservationMetadata.Freshness.CURRENT, "SERVER_LOADED", NOW + 100);
        return new WorldObservationService.Response(WorldObservationService.Status.OK, topic,
                "budget-fixture", 14, WorldObservationService.Coverage.FULL, null, items, 56, metadata);
    }
}
