package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** Real registry and village rendering; not a final model request or Minecraft scan. */
class RddVillageHistoryConsumerTest {
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
