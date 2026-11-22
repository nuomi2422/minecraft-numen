package com.dwinovo.numen.plugins.rdd;

import com.dwinovo.numen.rdd.api.AssetScope;
import com.dwinovo.numen.rdd.api.Observation;
import com.dwinovo.numen.rdd.core.AssetRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RddAssetStoreTest {
    @TempDir Path directory;

    @Test void persistsReusableWorldAssetsButNotEphemeralInventory() throws Exception {
        UUID companion = UUID.randomUUID();
        AssetRegistry registry = new AssetRegistry();
        registry.apply(observation("world_base", Map.of("kind", "base", "label", "home",
                        "dimension", "minecraft:overworld", "x", 1, "y", 64, "z", 2,
                        "refresh_policy", "LAZY")),
                "base|overworld|1", AssetScope.GLOBAL, null);
        registry.apply(observation("inventory_scan", Map.of("count", 64)),
                "minecraft:cobblestone", AssetScope.GLOBAL, "s1");

        RddAssetStore.save(directory, companion, registry);
        AssetRegistry restored = RddAssetStore.load(directory, companion);

        assertTrue(restored.get("base|overworld|1").isPresent());
        assertTrue(restored.get("minecraft:cobblestone").isEmpty());
    }

    @Test void contextIsBoundedAndExplainsLazyEvidence() {
        AssetRegistry registry = new AssetRegistry();
        registry.apply(observation("world_machine", Map.of("kind", "machine", "label", "minecraft:furnace",
                        "dimension", "minecraft:overworld", "x", 3, "y", 65, "z", 4,
                        "refresh_policy", "LAZY", "summary", "coal=8")),
                "machine", AssetScope.GLOBAL, null);

        String context = RddAssetContext.render(registry, 600);
        assertTrue(context.contains("<known_world_assets>"));
        assertTrue(context.contains("minecraft:furnace"));
        assertTrue(context.contains("re-check"));
        assertTrue(context.length() <= 600);
    }

    @Test void differentWorldsAtSameCoordinatesMustNotCollapseIntoOneLead() {
        AssetRegistry registry = new AssetRegistry();
        registry.apply(new Observation("old", "world_machine", "test", "minecraft:overworld", 10L,
                Map.of("kind", "machine", "label", "furnace", "dimension", "minecraft:overworld",
                        "world_id", "world-A", "x", 3, "y", 65, "z", 4, "summary", "world-A-marker")),
                "asset-A", AssetScope.GLOBAL, null);
        registry.apply(new Observation("new", "world_machine", "test", "minecraft:overworld", 20L,
                Map.of("kind", "machine", "label", "furnace", "dimension", "minecraft:overworld",
                        "world_id", "world-B", "x", 3, "y", 65, "z", 4, "summary", "world-B-marker")),
                "asset-B", AssetScope.GLOBAL, null);
        String context = RddAssetContext.render(registry, 2000);
        assertTrue(context.contains("world-A-marker"), "one world's historical lead must not replace another's");
        assertTrue(context.contains("world-B-marker"), "both distinct worlds must remain visible as historical leads");
        assertTrue(context.contains("world=world-A") && context.contains("world=world-B"));
        assertTrue(context.contains("observed_at=10") && context.contains("observed_at=20"));
        assertTrue(context.contains("scope=UNKNOWN coverage=UNKNOWN"));
    }

    @Test void newerNearbyVillageObservationIsNotHiddenByRicherOldLead() {
        AssetRegistry registry = new AssetRegistry();
        registry.apply(new Observation("old", "world_village", "rdd_world_observer", "minecraft:overworld", 10L,
                Map.of("kind", "village", "label", "minecraft:village_plains", "dimension", "minecraft:overworld",
                        "x", 15, "y", 64, "z", 0, "resources", Map.of("villagers", 10), "summary", "old villagers=10")),
                "village|minecraft:overworld|0,0", AssetScope.GLOBAL, null);
        registry.apply(new Observation("new", "world_village", "rdd_world_observer", "minecraft:overworld", 20L,
                Map.of("kind", "village", "label", "minecraft:village_plains", "dimension", "minecraft:overworld",
                        "x", 16, "y", 64, "z", 0, "resources", Map.of("villagers", 0), "summary", "new villagers=0")),
                "village|minecraft:overworld|1,0", AssetScope.GLOBAL, null);
        String context = RddAssetContext.render(registry, 2000);
        assertTrue(context.contains("new villagers=0"), "newer scoped observation must not disappear behind richer history");
        assertTrue(context.contains("old villagers=10"), "different-origin historical lead must remain distinct");
        assertTrue(context.contains("source=rdd_world_observer"));
    }

    @Test void missingCoordinatesDoNotCollapseWithRealOrigin() {
        AssetRegistry registry = new AssetRegistry();
        registry.apply(observation("world_machine", Map.of("kind", "machine", "label", "furnace",
                "dimension", "overworld", "summary", "unknown-position")), "unknown", AssetScope.GLOBAL, null);
        registry.apply(observation("world_machine", Map.of("kind", "machine", "label", "furnace",
                "dimension", "overworld", "x", 0, "y", 64, "z", 0, "summary", "known-origin")),
                "origin", AssetScope.GLOBAL, null);
        String context = RddAssetContext.render(registry, 2000);
        assertTrue(context.contains("unknown-position"));
        assertTrue(context.contains("known-origin"));
    }

    @Test void sameExactObservationIdentityKeepsOnlyLatestAndRemainsBounded() {
        AssetRegistry registry = new AssetRegistry();
        registry.apply(new Observation("one", "world_machine", "test", "overworld", 10L,
                Map.of("kind", "machine", "label", "furnace", "dimension", "overworld", "x", 0, "y", 64, "z", 0, "summary", "old")),
                "same", AssetScope.GLOBAL, null);
        registry.apply(new Observation("two", "world_machine", "test", "overworld", 20L,
                Map.of("kind", "machine", "label", "furnace", "dimension", "overworld", "x", 0, "y", 64, "z", 0, "summary", "new")),
                "same", AssetScope.GLOBAL, null);
        String context = RddAssetContext.render(registry, 600);
        assertTrue(context.contains("new"));
        assertFalse(context.contains("details=old"));
        assertTrue(context.contains("re-check"));
        assertTrue(context.length() <= 600);
    }

    @Test void budgetWithNearbyDuplicatesStaysBoundedAndDoesNotPretendComplete() {
        AssetRegistry registry = new AssetRegistry();
        for (int i = 0; i < 20; i++) {
            registry.apply(new Observation("scan-" + i, "world_village", "test", "overworld", 100L + i,
                    Map.of("kind", "village", "label", "v", "dimension", "overworld", "x", i, "y", 64, "z", 0,
                            "summary", "village-" + i)), "village-" + i, AssetScope.GLOBAL, null);
        }
        registry.apply(new Observation("base", "world_base", "test", "overworld", 1L,
                Map.of("kind", "base", "label", "home", "dimension", "overworld", "x", 100, "y", 64, "z", 0,
                        "summary", "important-base")), "base", AssetScope.GLOBAL, null);
        String context = RddAssetContext.render(registry, 600);
        assertTrue(context.length() <= 600);
        assertTrue(context.contains("re-check"));
        assertTrue(context.contains("truncated=YES"), "bounded output must explicitly disclose omitted leads");
    }

    @Test void everySmallBudgetIsRespectedAndOmissionIsExplicit() {
        AssetRegistry registry = new AssetRegistry();
        registry.apply(observation("world_machine", Map.of("kind", "machine", "label", "furnace",
                "dimension", "overworld", "x", 3, "y", 65, "z", 4, "summary", "fixture")),
                "machine", AssetScope.GLOBAL, null);
        for (int budget : new int[]{-1, 0, 63, 64, 100, 120, 140, 159, 160, 600}) {
            String result = RddAssetContext.render(registry, budget);
            assertTrue(result.length() <= Math.max(0, budget), "budget=" + budget + " length=" + result.length());
            if (!result.isEmpty() && !result.contains("asset_id=machine"))
                assertTrue(result.contains("not_delivered=YES"), "nondelivery must be visible at budget=" + budget);
        }
    }

    @Test void plannerBudgetOffersWholeEntryOrExplicitNondelivery() {
        AssetRegistry empty = new AssetRegistry();
        assertEquals("", RddAssetContext.render(empty, 160));
        AssetRegistry registry = new AssetRegistry();
        registry.apply(observation("world_machine", Map.of("kind", "machine", "label", "furnace",
                "dimension", "overworld", "x", 3, "y", 65, "z", 4, "summary", "fixture")),
                "machine", AssetScope.GLOBAL, null);
        String planner = RddAssetContext.render(registry, 160);
        assertTrue(planner.length() <= 160);
        assertTrue(planner.contains("asset_id=machine") || planner.contains("not_delivered=YES"), planner);
        for (int budget : new int[]{64,160,600,1200}) {
            String result = RddAssetContext.render(registry, budget);
            assertTrue(result.length() <= budget, "budget=" + budget + " actual=" + result.length());
        }
        AssetRegistry longField = new AssetRegistry();
        longField.apply(observation("world_machine", Map.of("kind", "machine", "label", "x".repeat(2000))),
                "id-" + "x".repeat(2000), AssetScope.GLOBAL, null);
        for (int budget : new int[]{64,160,600,1200}) {
            String result = RddAssetContext.render(longField, budget);
            assertTrue(result.length() <= budget);
            assertTrue(result.contains("not_delivered=YES") || result.contains("truncated=YES"));
        }
    }

    @Test void plannerShortEntryCanBeExpandedThroughActualAssetTool() {
        AssetRegistry registry = new AssetRegistry();
        registry.apply(new Observation("fixture", "world_machine", "source-fixture", "overworld", 1000L,
                Map.of("kind", "machine", "label", "furnace", "dimension", "overworld", "world_id", "world-A",
                        "x", 3, "y", 65, "z", 4, "summary", "local scan")),
                "machine", AssetScope.GLOBAL, null);
        String planner = RddAssetContext.render(registry, 160);
        assertTrue(planner.contains("rdd_assets detail asset_id=machine"), planner);
        var args = new com.google.gson.JsonObject(); args.addProperty("action", "detail"); args.addProperty("asset_id", "machine");
        var result = com.google.gson.JsonParser.parseString(RddAssetsTool.dispatch(args, registry)).getAsJsonObject();
        assertTrue(result.get("success").getAsBoolean());
        var data = result.getAsJsonObject("data");
        var identity = data.getAsJsonObject("identity");
        assertEquals("world-A", identity.get("world_id").getAsString());
        assertEquals("source-fixture", identity.get("source").getAsString());
        assertEquals(1000L, identity.get("observed_at").getAsLong());
        assertEquals("UNKNOWN", identity.get("scope").getAsString());
        assertEquals("UNKNOWN", identity.get("coverage").getAsString());
        assertTrue(data.getAsJsonObject("asset").toString().contains("local scan"));
    }

    private static Observation observation(String type, Map<String, Object> value) {
        return new Observation("obs-" + type, type, "test", "minecraft:overworld", 1L, value);
    }
}
