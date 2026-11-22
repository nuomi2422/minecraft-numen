package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.api.WorldContextStamp;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import com.dwinovo.numen.task.*;
import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real projector, task record, registry and public detail dispatcher; controlled identity/result, no game or event bus. */
class RddCompletedMoveAssetTest {
    static class Record extends TaskRecord { Record() { super("goto", "fixture", 100); } }
    static Map<String,Object> fact() {
        return Map.of("world_id", "world", "server_session", "session", "epoch", 7L,
                "dimension", "overworld", "direction", "start_to_end_only", "route_proven", false,
                "start", Map.of("x", 1.0, "y", 64.0, "z", 2.0), "end", Map.of("x", 5.0, "y", 64.0, "z", 2.0));
    }
    @Test void successProjectsOnceAndDetailRemainsHistoricalEndpoints() {
        UUID id = UUID.randomUUID(); var token = new WorldContextStamp.Token(Map.of("world_id", "world", "server_session", "session"), 7);
        var record = new Record(); record.bindIdentity(token); record.setState(TaskState.SUCCESS);
        record.setResult(TaskResult.ok("done", Map.of("completed_move", fact())));
        var registry = new AssetRegistry();
        assertTrue(RddCompletedMoveAsset.project(id, record.publicId(), record, token, "overworld", registry, 100));
        assertFalse(RddCompletedMoveAsset.project(id, record.publicId(), record, token, "overworld", registry, 200));
        assertEquals(1, registry.snapshot().size());
        var args = new JsonObject(); args.addProperty("action", "detail"); args.addProperty("asset_id", registry.snapshot().getFirst().assetId());
        String detail = RddAssetsTool.dispatch(args, registry);
        assertTrue(JsonParser.parseString(detail).getAsJsonObject().get("success").getAsBoolean(), detail);
        assertTrue(detail.contains("start_to_end_only") && detail.contains("route_proven") && detail.contains("false"), detail);
        assertTrue(detail.contains("not a continuous track") && detail.contains("world_id"), detail);
    }
    @Test void failedUnknownWrongIdentityAndNonfiniteNeverProject() {
        UUID id = UUID.randomUUID(); var token = new WorldContextStamp.Token(Map.of("world_id", "world", "server_session", "session"), 7);
        var record = new Record(); record.bindIdentity(token); record.setState(TaskState.SUCCESS);
        var registry = new AssetRegistry(); record.setResult(TaskResult.ok("done", Map.of("completed_move", fact())));
        assertFalse(RddCompletedMoveAsset.project(id, record.publicId(), record, null, "overworld", registry, 100));
        assertFalse(RddCompletedMoveAsset.project(id, record.publicId(), record, token, "nether", registry, 100));
        assertFalse(RddCompletedMoveAsset.project(id, "other", record, token, "overworld", registry, 100));
        assertFalse(RddCompletedMoveAsset.project(id, record.publicId(), record, new WorldContextStamp.Token(token.identity(), 8), "overworld", registry, 100));
        var bad = new HashMap<>(fact()); bad.put("end", Map.of("x", Double.NaN, "y", 64, "z", 0));
        record.setResult(TaskResult.ok("done", Map.of("completed_move", bad)));
        assertFalse(RddCompletedMoveAsset.project(id, record.publicId(), record, token, "overworld", registry, 100));
        record.setState(TaskState.CANCELLED); record.setResult(TaskResult.cancelled("cancel"));
        assertFalse(RddCompletedMoveAsset.project(id, record.publicId(), record, token, "overworld", registry, 100));
        assertTrue(registry.snapshot().isEmpty());
    }
}
