package com.dwinovo.numen.plugins.worldstate.tool;

import com.dwinovo.numen.api.observation.ObservationBatchReader;
import com.dwinovo.numen.api.observation.ObservationMetadata;
import com.dwinovo.numen.api.observation.WorldObservationService.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Published schema -> parser -> capability path, without an MC server (2026-11-05). */
class WorldStateBatchTest {
    private static final WorldStateBatch.Caller SELF = new WorldStateBatch.Caller(
            "a43d753e-0000-0000-0000-000000000001", "world", "session", "minecraft:overworld", 0, 64, 0);
    private static com.google.gson.JsonObject json(String text) { return JsonParser.parseString(text).getAsJsonObject(); }
    private static String wrapper(String entry) { return "{\"queries\":[" + entry + "]}"; }

    @Test void ownInventoryUsesAuthenticatedCallerNotConsumer() {
        var q = WorldStateBatch.parse(json(wrapper("{\"topic\":\"containers\",\"container\":\"player:" + SELF.companionId() + "\"}")), SELF, "q");
        assertEquals("world", q.expectedWorldId()); assertEquals("session", q.expectedServerSession());
        assertEquals(SELF.dimension(), q.requests().getFirst().dimension());
        assertEquals("world-state-tool", q.requests().getFirst().consumer());
    }
    @Test void otherInventoryDimensionAndFarBoxesAreRejected() {
        for (String e : List.of(
                "{\"topic\":\"containers\",\"container\":\"player:someone-else\"}",
                "{\"topic\":\"environment\",\"dimension\":\"minecraft:the_nether\"}",
                "{\"topic\":\"entities\",\"x1\":-33,\"y1\":64,\"z1\":0,\"x2\":1,\"y2\":64,\"z2\":1}",
                "{\"topic\":\"containers\",\"container\":\"block:1000,64,0\"}"))
            assertThrows(IllegalArgumentException.class, () -> WorldStateBatch.parse(json(wrapper(e)), SELF, "q"));
    }
    @Test void nearBoxUsesSameCanonicalCacheKey() {
        var q = WorldStateBatch.parse(json(wrapper("{\"topic\":\"blocks\",\"block\":\"minecraft:chest\",\"x1\":1,\"y1\":65,\"z1\":1,\"x2\":-1,\"y2\":63,\"z2\":-1}")), SELF, "q");
        assertEquals("-1,63,-1,1,65,1", q.requests().getFirst().selector().get("box"));
    }
    @Test void invalidBudgetAndFractionsFailRatherThanTruncate() {
        for (String budget : List.of("0", "-1", "1.5", "\"10\"", "100001")) {
            var a = json("{\"queries\":[{\"topic\":\"environment\"}],\"max_age_ms\":" + budget + "}");
            assertThrows(IllegalArgumentException.class, () -> WorldStateBatch.parse(a, SELF, "q"));
        }
    }
    @Test void noQueriesOrMissingTopicFails() {
        assertThrows(IllegalArgumentException.class, () -> WorldStateBatch.parse(json("{}"), SELF, "q"));
        assertThrows(IllegalArgumentException.class, () -> WorldStateBatch.parse(json(wrapper("{}")), SELF, "q"));
    }
    @Test @SuppressWarnings("unchecked") void schemaPublishesBatchFieldsAndDoesNotRequireThemForLegacyActions() {
        var schema = WorldStateSchema.build();
        var props = (Map<String, Object>) schema.get("properties");
        assertEquals(List.of("action"), schema.get("required"));
        var queries = (Map<String, Object>) props.get("queries");
        assertEquals(16, queries.get("maxItems"));
        var item = (Map<String, Object>) queries.get("items");
        assertEquals(List.of("topic"), item.get("required"));
        assertEquals(false, item.get("additionalProperties"));
        assertFalse(((Map<String, Object>) item.get("properties")).containsKey("queries"));
        assertTrue(((List<?>) ((Map<?, ?>) props.get("action")).get("enum")).contains("batch_read"));
    }
    @Test void parsedQueryActuallyCallsOnlyReadAndPropagatesIdentity() {
        var q = WorldStateBatch.parse(json(wrapper("{\"topic\":\"environment\"}")), SELF, "q");
        AtomicInteger calls = new AtomicInteger();
        var r = ObservationBatchReader.read(request -> {
            calls.incrementAndGet();
            return new Response(Status.OK, "environment", "s", 1, Coverage.FULL, null, List.of(Item.of(Map.of("day_time", 1, "weather", "clear", "dimension", SELF.dimension()))), 0,
                    new ObservationMetadata("world", "session", SELF.dimension(), "", "env", "key", 1, 1000,
                            ObservationMetadata.Freshness.CURRENT, "SERVER_LOADED", 2000));
        }, q, () -> 1100, () -> 0);
        assertTrue(r.coherent()); assertEquals(1, calls.get());
        String wire = com.dwinovo.numen.task.TaskResult.ok("read", Map.of("result", WorldStateBatch.resultData(r))).toJson();
        var sent = json(wire).getAsJsonObject("data").get("result");
        assertTrue(sent.isJsonObject(), "a Java record toString is not a structured tool reply");
        var first = sent.getAsJsonObject().getAsJsonArray("responses").get(0).getAsJsonObject();
        assertEquals("session", first.getAsJsonObject("metadata").get("serverSession").getAsString());
        assertEquals("clear", first.getAsJsonArray("items").get(0).getAsJsonObject()
                .getAsJsonObject("values").get("weather").getAsString());
    }
}
