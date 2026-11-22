package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Real registry and village rendering; not a final model request or Minecraft scan. */
class RddVillageHistoryConsumerTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;

    @Test void restoredMissingVillageFieldsStayUnknown() throws Exception {
        var id = java.util.UUID.randomUUID();
        AssetRegistry registry = new AssetRegistry();
        registry.apply(new Observation("missing", "world_village", "legacy", "minecraft:overworld", 10L,
                Map.of("kind", "village", "dimension", "minecraft:overworld", "world_id", "world-A")),
                "missing", AssetScope.GLOBAL, null);
        RddAssetStore.save(temp, id, registry);
        String text = RddVillageFacts.blockFor(RddAssetStore.load(temp, id));
        assertAll(
                () -> assertTrue(text.contains("pos=UNKNOWN"), text),
                () -> assertTrue(text.contains("villagers=UNKNOWN"), text),
                () -> assertTrue(text.contains("score=UNKNOWN（不可完整比较）"), text),
                () -> assertFalse(text.contains("pos=0,0,0"), text),
                () -> assertFalse(text.contains("villagers=0"), text));
    }

    @Test void restoredCompleteVillageKeepsKnownValues() throws Exception {
        var id = java.util.UUID.randomUUID();
        AssetRegistry registry = new AssetRegistry();
        registry.apply(new Observation("complete", "world_village", "legacy", "minecraft:overworld", 20L,
                Map.of("kind", "village", "dimension", "minecraft:overworld", "world_id", "world-A",
                        "x", 0, "y", 64, "z", 0, "resources", Map.of("villagers", 0))),
                "complete", AssetScope.GLOBAL, null);
        RddAssetStore.save(temp, id, registry);
        String text = RddVillageFacts.blockFor(RddAssetStore.load(temp, id));
        assertTrue(text.contains("pos=0,64,0") && text.contains("villagers=0"), text);
        assertTrue(text.contains("score=UNKNOWN（不可完整比较）"), text);
    }

    @Test void candidatesRemainDistinctAndDiscloseHistoricalScope() {
        AssetRegistry registry = new AssetRegistry();
        registry.apply(new Observation("old", "world_village", "rdd_world_observer", "minecraft:overworld", 10L,
                Map.of("kind", "village", "dimension", "minecraft:overworld", "world_id", "world-A",
                        "x", 15, "y", 64, "z", 0, "resources", Map.of("villagers", 10))),
                "village-old", AssetScope.GLOBAL, null);
        registry.apply(new Observation("new", "world_village", "rdd_world_observer", "minecraft:overworld", 20L,
                Map.of("kind", "village", "dimension", "minecraft:overworld", "world_id", "world-A",
                        "x", 16, "y", 64, "z", 0, "resources", Map.of("villagers", 0))),
                "village-new", AssetScope.GLOBAL, null);
        String text = RddVillageFacts.blockFor(registry);
        assertTrue(text.contains("village-old") && text.contains("village-new"), text);
        assertTrue(text.indexOf("village-old") < text.indexOf("village-new"), "historical rank still deterministic: " + text);
        assertAll(
                () -> assertTrue(text.contains("历史") && text.contains("重查"), text),
                () -> assertTrue(text.contains("observed_at=10") && text.contains("observed_at=20"), text),
                () -> assertTrue(text.contains("world=world-A") && text.contains("coverage=UNKNOWN"), text),
                () -> assertTrue(text.contains("source=rdd_world_observer"), text));
        assertTrue(text.contains("</village_candidates>"), text);
    }
}
