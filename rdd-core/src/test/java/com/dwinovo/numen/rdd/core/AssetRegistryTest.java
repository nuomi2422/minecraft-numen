package com.dwinovo.numen.rdd.core;

import com.dwinovo.numen.rdd.api.*;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AssetRegistryTest {
    @Test void keepsObservationHistoryAndUsableProjection() {
        var registry = new AssetRegistry();
        var observation = new Observation("o1", "inventory", "test", "world", 1L, Map.of("count", 3));
        assertTrue(registry.apply(observation, "stone", AssetScope.TASK_BOUND, "s1"));
        assertEquals(1, registry.history().size());
        assertEquals(1, registry.usable().size());
        registry.markUnknown("stone");
        assertTrue(registry.usable().isEmpty());
        registry.apply(observation, "stone", AssetScope.GLOBAL, "s1");
        assertEquals(AssetStatus.OBSERVED, registry.get("stone").orElseThrow().status());
        assertEquals(AssetScope.GLOBAL, registry.get("stone").orElseThrow().scope());
        registry.invalidate("stone");
        assertTrue(registry.history().size() >= 2);
    }

    @Test void currentAssetsRoundTripWithoutPersistingAnUnboundedHistory() {
        var registry = new AssetRegistry();
        for (int i = 0; i < 600; i++) {
            registry.apply(new Observation("o" + i, "world_machine", "test", "world", i,
                    Map.of("count", i)), "machine", AssetScope.GLOBAL, null);
        }

        assertEquals(512, registry.history().size());
        var restored = AssetRegistry.fromJson(registry.toJson());
        var entry = restored.get("machine").orElseThrow();
        assertEquals(599.0, ((Number) entry.observation().value().get("count")).doubleValue());
        assertEquals(AssetStatus.OBSERVED, entry.status());
    }

    @Test void forgetIsIdempotent() {
        var registry = new AssetRegistry();
        registry.apply(new Observation("o", "world_base", "test", "world", 1, Map.of()),
                "base", AssetScope.GLOBAL, null);
        assertTrue(registry.forget("base"));
        assertFalse(registry.forget("base"));
    }
}
